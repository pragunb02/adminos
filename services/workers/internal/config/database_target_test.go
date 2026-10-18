package config

import (
	"strings"
	"testing"
)

// Test credentials are assembled at run time, so no connection string with a password is in the source.
const (
	testUser = "dbowner"
	testPass = "fake" + "-pw-" + "4417"
)

func withCreds(scheme, userInfo, rest string) string { return scheme + "://" + userInfo + "@" + rest }

func TestDatabaseTargetShowsOnlyHostPortAndDatabase(t *testing.T) {
	cases := []struct {
		name, raw, want string
	}{
		{"url with user and password", withCreds("postgresql", testUser+":"+testPass, "db.internal:5433/adminos"), "db.internal:5433/adminos"},
		{"postgres scheme, default port", withCreds("postgres", testUser+":"+testPass, "db.internal/adminos"), "db.internal:5432/adminos"},
		{"user without password", withCreds("postgres", testUser, "db.internal:5432/adminos"), "db.internal:5432/adminos"},
		{"no user info", "postgres://db.internal:5432/adminos", "db.internal:5432/adminos"},
		{"password and options in the query are dropped", "postgres://db.internal/adminos?sslmode=disable&password=" + testPass + "&user=" + testUser, "db.internal:5432/adminos"},
		{"unescaped @ in the password", withCreds("postgres", testUser+":"+testPass+"@x", "db.internal/adminos"), MalformedDatabaseURL},
		{"percent-encoded password", withCreds("postgres", testUser+":"+strings.ReplaceAll(testPass, "-", "%2F"), "db.internal/adminos"), "db.internal:5432/adminos"},
		{"key=value DSN", "host=db.internal port=5434 dbname=adminos user=" + testUser + " password=" + testPass, "db.internal:5434/adminos"},
		{"several hosts", withCreds("postgres", testUser+":"+testPass, "a.internal:5432,b.internal:5433/adminos"), "a.internal:5432/adminos (+1 fallback hosts)"},
		{"no database name", withCreds("postgres", testUser+":"+testPass, "db.internal:5432"), "db.internal:5432/-"},
		{"sslmode=prefer's TLS fallback is not another host", withCreds("postgres", testUser+":"+testPass, "db.internal/adminos?sslmode=prefer"), "db.internal:5432/adminos"},
		{"database named like the user", withCreds("postgres", testUser+":"+testPass, "db.internal/"+testUser), "db.internal:5432/(named after the user)"},
		{"unix socket", "postgres:///adminos?host=/var/run/postgresql&user=" + testUser, "/var/run/postgresql:5432/adminos"},
		{"empty", "", "(not set)"},
		{"blank", "   ", "(not set)"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := DatabaseTarget(c.raw); got != c.want {
				t.Fatalf("DatabaseTarget = %q, want %q", got, c.want)
			}
		})
	}
}

func TestDatabaseTargetNeverShowsUserInfoOrTheParseError(t *testing.T) {
	hostile := []string{
		withCreds("postgres", testUser+":"+testPass, "db.internal:5432/adminos"),
		withCreds("postgres", testUser+":12/"+testPass, "db.internal/adminos"),   // unescaped '/' in the password
		withCreds("postgres", testUser+":12?"+testPass, "db.internal/adminos"),   // unescaped '?'
		withCreds("postgres", testUser+":"+testPass+"@x", "db.internal/adminos"), // unescaped '@'
		withCreds("postgres", testUser+"@"+testPass, "db.internal/adminos"),      // '@' in the user name
		withCreds("postgres", testUser+":12#"+testPass, "db.internal/adminos"),   // unescaped '#'
		withCreds("postgres", testUser+":"+testPass, "db.internal:notaport/adminos"),
		"postgres://" + testUser + ":" + testPass + "@[::1/adminos",
		"host=db.internal user=" + testUser + " password='" + testPass,
		"user=" + testUser + " password=" + testPass + " bogus",
		"mysql://" + testUser + ":" + testPass + "@db.internal/adminos",
		"not a url " + testUser + " " + testPass,
	}
	for _, raw := range hostile {
		got := DatabaseTarget(raw)
		for _, secret := range []string{testUser, testPass, "4417", "fake"} {
			if strings.Contains(got, secret) {
				t.Errorf("DatabaseTarget(%d chars) = %q shows %q", len(raw), got, secret)
			}
		}
	}
}

func TestDatabaseTargetRefusesUserInfoOutsideTheAuthority(t *testing.T) {
	for _, sep := range []string{"/", "?", "#", "@"} {
		raw := withCreds("postgres", testUser+":12"+sep+testPass, "db.internal/adminos")
		if got := DatabaseTarget(raw); got != MalformedDatabaseURL {
			t.Errorf("separator %q: DatabaseTarget = %q, want the malformed message", sep, got)
		}
	}
}

func TestDatabaseTargetSaysUnreadableWithoutQuotingTheValue(t *testing.T) {
	if got := DatabaseTarget(withCreds("postgres", testUser+":"+testPass, "db.internal:notaport/adminos")); got != UnreadableDatabaseURL {
		t.Fatalf("DatabaseTarget = %q, want %q", got, UnreadableDatabaseURL)
	}
}

func TestDatabaseTargetCannotForgeLogLines(t *testing.T) {
	got := DatabaseTarget("postgres://db.internal/adminos%0AFAKE%20LOG%20LINE")
	if strings.ContainsAny(got, "\n\r ") {
		t.Fatalf("DatabaseTarget kept a control or space character: %q", got)
	}
	if got != "db.internal:5432/adminos?FAKE?LOG?LINE" {
		t.Fatalf("DatabaseTarget = %q", got)
	}
}

func TestLogSafeMarksEmptyAndReplacesUnsafeRunes(t *testing.T) {
	if logSafe("") != "-" {
		t.Fatal("empty should be shown as -")
	}
	if got := logSafe("db\u202e.internal\t"); got != "db?.internal?" {
		t.Fatalf("logSafe = %q", got)
	}
}
