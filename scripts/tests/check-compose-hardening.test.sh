#!/usr/bin/env bash
# Tests for scripts/check-compose-hardening.sh (AOS-622). Needs docker (compose v2) and jq.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/../check-compose-hardening.sh"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

pass=0; fail=0
digest="alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6"

# service <name> [lines to drop, as a regex] [extra lines]: a hardened third-party service, minus/plus lines.
service() {
  local name="$1" drop="${2:-^$}" extra="${3:-}"
  { printf '  %s:\n' "$name"
    printf '%s\n' "    image: $digest" "    user: \"1000:1000\"" "    read_only: true" '    cap_drop: ["ALL"]' \
      '    security_opt: ["no-new-privileges:true"]' "    mem_limit: 128m" "    cpus: 0.5" "    pids_limit: 64" \
      "    healthcheck:" '      test: ["CMD", "true"]' | grep -Ev -- "$drop" || true
    if [[ -n "$extra" ]]; then printf "%s\n" "$extra"; fi
  }
}
stack() { local name="$1"; shift; { echo "services:"; "$@"; } > "$work/$name.yml"; }

expect() {   # expect <ok|fail> <stack> [needle]
  local want="$1" name="$2" needle="${3:-}" out rc=0 got=ok
  out="$("$check" "$work/$name.yml" 2>&1)" || rc=$?
  [[ $rc -ne 0 ]] && got=fail
  if [[ "$got" == "$want" && ( -z "$needle" || "$out" == *"$needle"* ) ]]; then pass=$((pass + 1)); echo "ok   - $name ($want)"
  else fail=$((fail + 1)); echo "FAIL - $name: wanted $want${needle:+ with \"$needle\"}, got $got"; sed 's/^/       /' <<<"$out"; fi
}

stack hardened service web
stack no-read-only service web 'read_only'
stack read-only-false service web 'read_only' '    read_only: false'
stack no-cap-drop service web 'cap_drop'
stack cap-drop-some service web 'cap_drop' '    cap_drop: ["NET_RAW"]'
stack no-nnp service web 'security_opt'
stack no-mem service web 'mem_limit'
stack no-cpus service web 'cpus'
stack no-pids service web 'pids_limit'
stack no-healthcheck service web 'healthcheck|test:'
stack healthcheck-disabled service web 'healthcheck|test:' $'    healthcheck:\n      disable: true'
stack tag-only service web 'image:' '    image: alpine:3.24'
stack no-user service web 'user:'
stack root-user service web 'user:' '    user: root'
stack uid0 service web 'user:' '    user: "0:0"'
stack deploy-limits service web 'mem_limit|cpus|pids_limit' $'    deploy:\n      resources:\n        limits:\n          memory: 128m\n          cpus: "0.5"\n          pids: 64'
stack worker-exception service gmail-worker 'healthcheck|test:'
stack exception-is-per-rule service gmail-worker 'healthcheck|test:|read_only'
two() { service ok; service debug 'cap_drop' '    profiles: ["debug"]'; }
stack profile-only two

expect ok   hardened "every service hardened"
expect fail no-read-only "web: missing read_only"
expect fail read-only-false "web: missing read_only"
expect fail no-cap-drop "web: missing cap_drop"
expect fail cap-drop-some "web: missing cap_drop"
expect fail no-nnp "web: missing no_new_privileges"
expect fail no-mem "web: missing mem_limit"
expect fail no-cpus "web: missing cpus"
expect fail no-pids "web: missing pids_limit"
expect fail no-healthcheck "web: missing healthcheck"
expect fail healthcheck-disabled "web: missing healthcheck"
expect fail tag-only "web: missing digest"
expect fail no-user "web: missing user"
expect fail root-user "web: missing user"
expect fail uid0 "web: missing user"
expect ok   deploy-limits
expect ok   worker-exception
expect fail exception-is-per-rule "gmail-worker: missing read_only"
expect fail profile-only "debug: missing cap_drop"

# Dockerfiles
df() { printf '%s\n' "$2" > "$work/$1.Dockerfile"; }
expect_df() {
  local want="$1" name="$2" needle="${3:-}" out rc=0 got=ok
  out="$("$check" --dockerfile "$work/$name.Dockerfile" 2>&1)" || rc=$?
  [[ $rc -ne 0 ]] && got=fail
  if [[ "$got" == "$want" && ( -z "$needle" || "$out" == *"$needle"* ) ]]; then pass=$((pass + 1)); echo "ok   - dockerfile $name ($want)"
  else fail=$((fail + 1)); echo "FAIL - dockerfile $name: wanted $want, got $got"; sed 's/^/       /' <<<"$out"; fi
}
df good "FROM $digest AS build
RUN true
FROM build AS test
FROM $digest
COPY --from=build /x /x
USER app"
df unpinned "FROM alpine:3.24
USER app"
df unpinned-builder "FROM golang:1.27-alpine AS builder
FROM $digest
USER app"
df no-user "FROM $digest"
df root-user "FROM $digest
USER root"
df user-only-in-builder "FROM $digest AS build
USER app
FROM $digest"
df platform "FROM --platform=linux/amd64 $digest
USER 10001"
expect_df ok good
expect_df fail unpinned "not pinned by digest"
expect_df fail unpinned-builder "golang:1.27-alpine is not pinned"
expect_df fail no-user "non-root USER"
expect_df fail root-user "non-root USER"
expect_df fail user-only-in-builder "non-root USER"
expect_df ok platform

# The repo itself: every stack and every Dockerfile.
rc=0; out="$("$check")" || rc=$?
if [[ $rc -eq 0 && "$out" != *FAIL* ]]; then pass=$((pass + 1)); echo "ok   - repo stacks and Dockerfiles"; else fail=$((fail + 1)); echo "FAIL - repo"; echo "$out"; fi

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
