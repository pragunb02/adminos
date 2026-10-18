package main

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"os/exec"
	"strings"
	"testing"
)

// TestWorkerRefusesToStartWithoutTheRedisPassword runs the real main() in a child process (AOS-634): without
// the Redis password (REDIS_PASSWORD or in REDIS_URL) it must exit non-zero before connecting to anything, and
// never print the URL or a password.
func TestWorkerRefusesToStartWithoutTheRedisPassword(t *testing.T) {
	if os.Getenv("AOS_WORKER_MAIN") == "1" {
		main()
		return
	}
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	pw := hex.EncodeToString(b) // random per run: no credential-shaped literal in the repo

	cases := []struct{ name, url, password, want string }{
		{"no URL", "", pw, "REDIS_URL is not set"},
		{"no password", "redis://redis:6379", "", "requires a password"},
		{"malformed, with a password", "redis://:" + pw + "@bad host:port", "", "not a valid"},
		{"two different passwords", "redis://:" + pw + "@redis:6379", pw + "0", "disagree"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			cmd := exec.Command(os.Args[0], "-test.run=^TestWorkerRefusesToStartWithoutTheRedisPassword$")
			cmd.Env = append(withoutRedis(os.Environ()), "AOS_WORKER_MAIN=1", "REDIS_URL="+c.url, "REDIS_PASSWORD="+c.password, "TOKEN_ENCRYPTION_KEY=")
			out, err := cmd.CombinedOutput()
			if err == nil {
				t.Fatalf("the worker started; output:\n%s", out)
			}
			if exit, ok := err.(*exec.ExitError); !ok || exit.ExitCode() == 0 {
				t.Fatalf("want a non-zero exit, got %v", err)
			}
			text := string(out)
			if !strings.Contains(text, "Refusing to start") || !strings.Contains(text, c.want) {
				t.Fatalf("want a refusal naming %q, got:\n%s", c.want, text)
			}
			if strings.Contains(text, pw) || (c.url != "" && strings.Contains(text, c.url)) {
				t.Fatalf("the output quotes REDIS_URL or its password:\n%s", text)
			}
		})
	}
}

func withoutRedis(env []string) []string {
	out := make([]string, 0, len(env))
	for _, kv := range env {
		if !strings.HasPrefix(kv, "REDIS_URL=") && !strings.HasPrefix(kv, "REDIS_PASSWORD=") {
			out = append(out, kv)
		}
	}
	return out
}
