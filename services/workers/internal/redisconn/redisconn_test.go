package redisconn

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
)

// secret is random per run, so no credential-shaped literal sits in the repo.
func secret(t *testing.T) string {
	t.Helper()
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(b)
}

func TestParseTakesREDIS_PASSWORDBesideABareURL(t *testing.T) {
	pw := secret(t)
	conn, err := Parse("redis://redis:6379/2", pw)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if conn.Addr() != "redis:6379" || conn.Asynq.Password != pw || conn.Asynq.DB != 2 {
		t.Fatalf("asynq options: addr=%q db=%d password set=%v", conn.Asynq.Addr, conn.Asynq.DB, conn.Asynq.Password == pw)
	}
	if conn.Bridge.Addr != "redis:6379" || conn.Bridge.Password != pw || conn.Bridge.DB != 2 {
		t.Fatalf("bridge options: addr=%q db=%d password set=%v", conn.Bridge.Addr, conn.Bridge.DB, conn.Bridge.Password == pw)
	}
}

func TestParseTakesThePasswordFromTheURL(t *testing.T) {
	pw := secret(t)
	for _, env := range []string{"", pw} { // alone, or the same value in both places
		conn, err := Parse("redis://:"+pw+"@127.0.0.1:6379", env)
		if err != nil {
			t.Fatalf("Parse: %v", err)
		}
		if conn.Asynq.Password != pw || conn.Bridge.Password != pw {
			t.Fatalf("the URL's password was not used")
		}
	}
}

func TestParseKeepsTLSForRediss(t *testing.T) {
	conn, err := Parse("rediss://cache.internal:6380", secret(t))
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if conn.Addr() != "cache.internal:6380" || conn.Bridge.TLSConfig == nil || conn.Asynq.TLSConfig == nil {
		t.Fatalf("addr %q, tls bridge %v asynq %v", conn.Addr(), conn.Bridge.TLSConfig != nil, conn.Asynq.TLSConfig != nil)
	}
}

func TestParseRefuses(t *testing.T) {
	pw, other := secret(t), secret(t)
	cases := []struct {
		name, url, password string
		want                error
	}{
		{"unset", "", pw, ErrNoURL},
		{"blank", "   ", pw, ErrNoURL},
		{"no password anywhere", "redis://redis:6379", "", ErrNoPassword},
		{"a blank REDIS_PASSWORD", "redis://redis:6379", "  ", ErrNoPassword},
		{"an empty URL password", "redis://:@redis:6379", "", ErrNoPassword},
		{"two different passwords", "redis://:" + pw + "@redis:6379", other, ErrConflict},
		{"a user name (asynq would drop it)", "redis://" + pw + "@redis:6379", other, ErrUserName},
		{"a user name and a password", "redis://default:" + pw + "@redis:6379", "", ErrUserName},
		{"malformed, with a password", "redis://:" + pw + "@bad host:port", "", ErrInvalid},
		{"not Redis", "http://:" + pw + "@example.com", "", ErrInvalid},
		{"Sentinel", "redis-sentinel://:" + pw + "@s1:26379?master=main", "", ErrInvalid},
		{"a control character", "redis://:" + pw + "@re\x7fdis:6379", "", ErrInvalid},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := Parse(c.url, c.password)
			if !errors.Is(err, c.want) {
				t.Fatalf("got %v, want %v", err, c.want)
			}
			if u := strings.TrimSpace(c.url); u != "" && strings.Contains(err.Error(), u) {
				t.Fatalf("the error quotes the URL")
			}
			if strings.Contains(err.Error(), pw) || strings.Contains(err.Error(), other) {
				t.Fatalf("the error quotes a password")
			}
		})
	}
}
