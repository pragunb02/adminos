#!/usr/bin/env bash
# Tests deploy/cloudflared/config.yml (AOS-616): the tunnel routes ONLY the Gmail webhook path to the API;
# everything else is a 404 at Cloudflare's edge. Uses cloudflared's own rule matcher, in Docker.
# Usage: scripts/tests/cloudflared-ingress.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config_dir="$(cd "$here/../../deploy/cloudflared" && pwd)"
# Keep in step with docker-compose.gmail-push.yml.
image="cloudflare/cloudflared:2026.10.1@sha256:a15a3b50b8dabd57ac2090e9484c0247dba9dfa1f0df4d298d4317f9d69eb2d5"

cf() { docker run --rm --network none -v "$config_dir:/etc/cloudflared:ro" "$image" tunnel --config /etc/cloudflared/config.yml "$@" 2>&1; }

pass=0; fail=0
ok() { pass=$((pass + 1)); echo "ok   - $1"; }
bad() { fail=$((fail + 1)); echo "FAIL - $1"; sed 's/^/       /' <<<"$2"; }

if out="$(cf ingress validate)"; then ok "ingress validates"; else bad "ingress validate" "$out"; fi

# expect_route <url> <api|404>
expect_route() {
  local url="$1" want="$2" out
  out="$(cf ingress rule "$url" || true)"
  case "$want" in
    api) if [[ "$out" == *"service: http://api-server:8080"* ]]; then ok "$url -> API"; else bad "$url should reach the API" "$out"; fi ;;
    404) if [[ "$out" == *"http_status:404"* && "$out" != *"api-server"* ]]; then ok "$url -> 404"; else bad "$url should be 404" "$out"; fi ;;
  esac
}

host="https://hooks.example.test"
expect_route "$host/api/v1/webhooks/gmail" api
expect_route "$host/api/v1/webhooks/gmail?token=x" api      # a query string doesn't change the path
expect_route "$host/" 404
expect_route "$host/login" 404
expect_route "$host/api/v1/auth/google" 404
expect_route "$host/api/v1/auth/refresh" 404
expect_route "$host/api/v1/ingest/sms/raw" 404
expect_route "$host/api/v1/webhooks/gmail/" 404
expect_route "$host/api/v1/webhooks/gmailx" 404
expect_route "$host/api/v1/webhooks/gmail/../../auth/refresh" 404
expect_route "$host/api/v1/webhooks/gmail%2F..%2F..%2Fauth" 404
expect_route "$host/API/V1/WEBHOOKS/GMAIL" 404
expect_route "$host/x/api/v1/webhooks/gmail" 404
expect_route "$host/health" 404
expect_route "https://any-other-host.example.test/api/v1/transactions" 404

# Exactly two rules: the webhook and the catch-all 404. A new rule must change this test on purpose.
rules="$(grep -cE '^[[:space:]]*-[[:space:]]+(path|hostname|service):' "$config_dir/config.yml")"
if [[ "$rules" -eq 2 ]]; then ok "exactly two ingress rules"; else bad "expected 2 ingress rules, found $rules" "$(cat "$config_dir/config.yml")"; fi

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
