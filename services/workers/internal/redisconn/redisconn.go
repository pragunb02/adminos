// Package redisconn turns REDIS_URL and REDIS_PASSWORD into the worker's Redis connection settings (AOS-634).
//
// Redis requires a password (compose sets `requirepass` from REDIS_PASSWORD), so:
//   - the password comes from REDIS_PASSWORD (compose) or from REDIS_URL's user info (a host-run worker);
//     if both are set they must agree; with neither, the worker doesn't start;
//   - the URL, the password, and any parser error (which quotes the URL) never reach a log or an error
//     message: callers log only Addr.
package redisconn

import (
	"errors"
	"net/url"
	"strings"

	"github.com/hibiken/asynq"
	"github.com/redis/go-redis/v9"
)

var (
	// ErrNoURL: REDIS_URL is empty.
	ErrNoURL = errors.New("REDIS_URL is not set")
	// ErrInvalid: REDIS_URL doesn't parse as a single-server redis://, rediss:// or redis-socket:// URL.
	ErrInvalid = errors.New("REDIS_URL is not a valid redis:// or rediss:// URL (value not shown)")
	// ErrUserName: REDIS_URL names a user. asynq.ParseRedisURI drops it, so the worker would sign in as another
	// user than the URL says; only the password (Redis's default user, `requirepass`) is supported.
	ErrUserName = errors.New("REDIS_URL names a user; only a password is supported (value not shown)")
	// ErrNoPassword: neither REDIS_PASSWORD nor REDIS_URL has a password, which Redis requires.
	ErrNoPassword = errors.New("Redis requires a password: set REDIS_PASSWORD (openssl rand -hex 32) in .env (value not shown)")
	// ErrConflict: REDIS_URL's password and REDIS_PASSWORD differ.
	ErrConflict = errors.New("REDIS_URL's password and REDIS_PASSWORD disagree; set only one (values not shown)")
)

// Conn holds one server's settings for both clients the worker uses.
type Conn struct {
	// Asynq is for the Asynq server and client.
	Asynq asynq.RedisClientOpt
	// Bridge is for the go-redis client that reads the API's job list.
	Bridge *redis.Options
}

// Addr is the host:port (or socket path): the only part of the connection that may be logged.
func (c Conn) Addr() string { return c.Asynq.Addr }

// Parse reads rawURL (REDIS_URL) with asynq.ParseRedisURI and applies password (REDIS_PASSWORD) when the URL
// has none. Its errors are fixed values that never quote either input.
func Parse(rawURL, password string) (Conn, error) {
	rawURL = strings.TrimSpace(rawURL)
	password = strings.TrimSpace(password)
	if rawURL == "" {
		return Conn{}, ErrNoURL
	}
	parsed, err := asynq.ParseRedisURI(rawURL)
	if err != nil {
		return Conn{}, ErrInvalid // not wrapped: url.Parse's error quotes the URL, password included
	}
	opt, ok := parsed.(asynq.RedisClientOpt)
	if !ok {
		return Conn{}, ErrInvalid // Sentinel or cluster URLs aren't used here
	}
	u, err := url.Parse(rawURL)
	if err != nil {
		return Conn{}, ErrInvalid
	}
	if u.User != nil && u.User.Username() != "" {
		return Conn{}, ErrUserName
	}
	switch {
	case opt.Password == "" && password == "":
		return Conn{}, ErrNoPassword
	case opt.Password != "" && password != "" && opt.Password != password:
		return Conn{}, ErrConflict
	case opt.Password == "":
		opt.Password = password
	}
	return Conn{
		Asynq: opt,
		Bridge: &redis.Options{
			Network:   opt.Network,
			Addr:      opt.Addr,
			Password:  opt.Password,
			DB:        opt.DB,
			TLSConfig: opt.TLSConfig,
		},
	}, nil
}
