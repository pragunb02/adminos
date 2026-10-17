#!/usr/bin/env bash
# Tests for scripts/check-compose-exposure.sh (AOS-616). Needs docker (compose v2) and jq.
# Usage: scripts/tests/check-compose-exposure.test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/../check-compose-exposure.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

pass=0; fail=0
fixture() { printf '%s\n' "$2" > "$work/$1.yml"; }

# expect <ok|fail> <fixture> [text the output must contain]
expect() {
  local want="$1" name="$2" needle="${3:-}" out rc=0
  out="$("$check" "$work/$name.yml" 2>&1)" || rc=$?
  local got=ok; [[ $rc -ne 0 ]] && got=fail
  if [[ "$got" == "$want" && ( -z "$needle" || "$out" == *"$needle"* ) ]]; then
    pass=$((pass + 1)); echo "ok   - $name ($want)"
  else
    fail=$((fail + 1)); echo "FAIL - $name: wanted $want${needle:+ with \"$needle\"}, got $got (rc=$rc)"; sed 's/^/       /' <<<"$out"
  fi
}

fixture loopback-ok 'services:
  web:
    image: alpine:3.24
    ports:
      - "127.0.0.1:3000:3000"
      - "[::1]:3001:3001"
      - target: 3002
        published: "3002"
        host_ip: 127.0.0.1
  internal:
    image: alpine:3.24
    expose: ["8000"]'
fixture bare-host-port 'services:
  web:
    image: alpine:3.24
    ports: ["3000:3000"]'
fixture container-port-only 'services:
  web:
    image: alpine:3.24
    ports: ["3000"]'
fixture wildcard-ipv4 'services:
  web:
    image: alpine:3.24
    ports: ["0.0.0.0:3000:3000"]'
fixture wildcard-ipv6 'services:
  web:
    image: alpine:3.24
    ports: ["[::]:3000:3000"]'
fixture lan-ip 'services:
  web:
    image: alpine:3.24
    ports: ["192.168.1.10:3000:3000"]'
fixture long-form-no-host-ip 'services:
  web:
    image: alpine:3.24
    ports:
      - target: 3000
        published: "3000"'
fixture wide-default-from-variable 'services:
  web:
    image: alpine:3.24
    ports: ["${AOS_TEST_BIND_ADDR:-0.0.0.0}:3000:3000"]'
fixture profile-only-exposure 'services:
  ok:
    image: alpine:3.24
  debug:
    image: alpine:3.24
    profiles: ["debug"]
    ports: ["8080:8080"]'
fixture host-network 'services:
  web:
    image: alpine:3.24
    network_mode: host'
fixture privileged 'services:
  web:
    image: alpine:3.24
    privileged: true'
fixture required-variable-ok 'services:
  web:
    image: alpine:3.24
    environment:
      SECRET: ${AOS_TEST_REQUIRED_SECRET:?}
    ports: ["127.0.0.1:3000:3000"]'
fixture unrenderable 'services:
  web:
    image: alpine:3.24
    ports:
      - target: not-a-port'

expect ok   loopback-ok "OK"
expect fail bare-host-port "all interfaces"
expect fail container-port-only "host port random"
expect fail wildcard-ipv4 "0.0.0.0"
expect fail wildcard-ipv6 "on ::"
expect fail lan-ip "192.168.1.10"
expect fail long-form-no-host-ip "all interfaces"
expect fail wide-default-from-variable "0.0.0.0"
expect fail profile-only-exposure "debug: publishes"
expect fail host-network "network_mode: host"
expect fail privileged "runs privileged"
expect ok   required-variable-ok "OK"
expect fail unrenderable "could not render"

# A missing file fails closed.
rc=0; "$check" "$work/does-not-exist.yml" >/dev/null 2>&1 || rc=$?
if [[ $rc -ne 0 ]]; then pass=$((pass + 1)); echo "ok   - missing file (fail)"; else fail=$((fail + 1)); echo "FAIL - missing file passed"; fi

# A stack of several files: an overlay that adds a wide publish fails the stack.
rc=0; "$check" "$work/loopback-ok.yml" "$work/bare-host-port.yml" >/dev/null 2>&1 || rc=$?
if [[ $rc -ne 0 ]]; then pass=$((pass + 1)); echo "ok   - overlay adds a wide publish (fail)"; else fail=$((fail + 1)); echo "FAIL - an overlay with a wide publish passed"; fi

# The real compose file passes alone and with every overlay, with every profile.
rc=0; out="$("$check")" || rc=$?
if [[ $rc -eq 0 && "$out" != *FAIL* ]]; then pass=$((pass + 1)); echo "ok   - repo docker-compose.yml and overlays"; else fail=$((fail + 1)); echo "FAIL - repo compose stacks"; echo "$out"; fi

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
