package config

import (
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5/pgconn"
)

// UnreadableDatabaseURL is what DatabaseTarget says when DATABASE_URL can't be parsed. The parse
// error itself is never shown: pgx quotes the connection string in it, user name included.
const UnreadableDatabaseURL = "(DATABASE_URL can't be parsed; check its format)"

// DatabaseTarget describes DATABASE_URL for logs as host:port/database, nothing else (AOS-637).
// No part of the user info (user name or password) and no parameters are ever included. It reads
// the value with pgx's own parser, so it handles every form pgx accepts (postgres:// URLs, key=value
// DSNs, unescaped '@' or '/' in a password) and names the server pgx will actually dial.
func DatabaseTarget(raw string) string {
	if strings.TrimSpace(raw) == "" {
		return "(not set)"
	}
	if userInfoOutsideAuthority(raw) {
		return MalformedDatabaseURL
	}
	cfg, err := pgconn.ParseConfig(raw)
	if err != nil {
		return UnreadableDatabaseURL
	}
	database := logSafe(cfg.Database)
	if cfg.User != "" && cfg.Database == cfg.User {
		// pgx defaults the database to the user name; showing it would show the user
		database = "(named after the user)"
	}
	target := logSafe(cfg.Host) + ":" + strconv.Itoa(int(cfg.Port)) + "/" + database
	if n := otherHosts(cfg); n > 0 {
		target += " (+" + strconv.Itoa(n) + " fallback hosts)"
	}
	return target
}

// otherHosts counts the fallback servers other than the primary. pgx also lists the same server
// again for its TLS-then-plain fallback (sslmode=prefer), which isn't another host.
func otherHosts(cfg *pgconn.Config) int {
	primary := hostPort(cfg.Host, cfg.Port)
	seen := map[string]bool{primary: true}
	for _, f := range cfg.Fallbacks {
		seen[hostPort(f.Host, f.Port)] = true
	}
	return len(seen) - 1
}

func hostPort(host string, port uint16) string { return host + ":" + strconv.Itoa(int(port)) }

// MalformedDatabaseURL is what DatabaseTarget says when a URL-form DATABASE_URL has an unescaped
// '@', '/', '?' or '#' in its password. The parser then reads part of the user info as the host,
// port or database, so none of those parts can be shown.
const MalformedDatabaseURL = "(DATABASE_URL looks malformed; percent-encode special characters in the password)"

// userInfoOutsideAuthority reports whether a URL-form DATABASE_URL has more than one '@' in its
// authority (the part between "://" and the first '/', '?' or '#'), or any '@' after it.
func userInfoOutsideAuthority(raw string) bool {
	i := strings.Index(raw, "://")
	if i < 0 {
		return false // a key=value DSN: pgx reads user and password as their own keys
	}
	rest := raw[i+3:]
	end := strings.IndexAny(rest, "/?#")
	if end < 0 {
		end = len(rest)
	}
	return strings.Count(rest[:end], "@") > 1 || strings.Contains(rest[end:], "@")
}

// logSafe keeps a host or database name to characters that can't forge or break a log line.
func logSafe(s string) string {
	if s == "" {
		return "-"
	}
	var b strings.Builder
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', strings.ContainsRune(".-_:/[]%", r):
			b.WriteRune(r)
		default:
			b.WriteRune('?')
		}
	}
	return b.String()
}
