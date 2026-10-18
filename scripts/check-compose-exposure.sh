#!/usr/bin/env bash
# Fails if docker compose would make a service reachable from beyond this machine (AOS-616, D-91).
#
# Usage:
#   scripts/check-compose-exposure.sh                 # the repo's docker-compose.yml alone, then with each
#                                                     # docker-compose.*.yml overlay (e.g. tailnet, gmail-push)
#   scripts/check-compose-exposure.sh a.yml [b.yml]   # one stack: compose -f a.yml -f b.yml
#
# Every profile is checked (`--profile '*'`). A stack fails when any service:
#   - publishes a port on anything but loopback (a bare "3000:3000", "3000", "0.0.0.0:…", "[::]:…", a LAN
#     address, a long-form entry without host_ip, or a host_ip from a variable whose default is wide);
#   - uses host networking (`network_mode: host` skips publishing, so it would bypass this check);
#   - runs privileged.
# Fail closed: a stack compose can't render is a failure too. Secrets in a real .env are never printed.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/compose-render.sh
source "$here/lib/compose-render.sh"

command -v docker >/dev/null || { echo "check-compose-exposure: docker is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "check-compose-exposure: jq is required" >&2; exit 2; }

# Prints one line per violation in the rendered compose JSON read from stdin.
violations() {
  jq -r '
    (.services // {}) | to_entries[] | .key as $svc | .value as $s |
    ( ($s.ports // [])[]
        | select((.host_ip // "") as $ip | ($ip != "127.0.0.1" and $ip != "::1"))
        | "\($svc): publishes container port \(.target) on \(if (.host_ip // "") == "" then "all interfaces" else .host_ip end) (host port \(.published // "random")); use 127.0.0.1:<host>:<container>"
    ),
    ( select($s.network_mode == "host") | "\($svc): uses network_mode: host, which exposes every port it listens on" ),
    ( select($s.privileged == true) | "\($svc): runs privileged" )
  '
}

# check_stack <file> [<file> ...]: returns 0 when the stack is loopback-only.
check_stack() {
  local label="$*" rendered found
  if ! rendered="$(render_stack "$@")"; then
    echo "FAIL $label: docker compose could not render it (run: docker compose -f … config)"
    return 1
  fi
  found="$(violations <<<"$rendered")"
  if [[ -n "$found" ]]; then
    echo "FAIL $label:"
    sed 's/^/  - /' <<<"$found"
    return 1
  fi
  echo "OK   $label: every published port is loopback-only; no host networking or privileged services"
}

if [[ $# -gt 0 ]]; then
  check_stack "$@"
else
  for_each_stack check_stack
fi
