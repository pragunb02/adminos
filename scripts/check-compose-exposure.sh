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
# Fail closed: a stack compose can't render is a failure too.
#
# Variables the files require (`${VAR:?}`) get a dummy value only when they're not set in the shell or in
# the .env next to the first file, so the check runs in CI without secrets. The rendered config holds
# secrets when a real .env exists, so it is only piped to jq and never printed.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
  local label="$*" file dir var
  local -a args=() dummies=()
  for file in "$@"; do
    if [[ ! -f "$file" ]]; then echo "FAIL $label: no such file $file"; return 1; fi
    args+=(-f "$file")
  done
  dir="$(cd "$(dirname "$1")" && pwd)"
  while IFS= read -r var; do
    [[ -z "$var" ]] && continue
    if [[ -n "${!var+x}" ]]; then continue; fi
    if [[ -f "$dir/.env" ]] && grep -Eq "^[[:space:]]*(export[[:space:]]+)?${var}=" "$dir/.env"; then continue; fi
    dummies+=("$var=/compose-exposure-check")   # absolute, so it also works as a bind-mount path
  done < <(cat "$@" | grep -oE '\$\{[A-Za-z_][A-Za-z0-9_]*:?\?' | sed -E 's/^\$\{//; s/:?\?$//' | sort -u)

  local rendered found
  if ! rendered="$(env ${dummies[@]+"${dummies[@]}"} docker compose "${args[@]}" --profile '*' config --format json 2>/dev/null)"; then
    echo "FAIL $label: docker compose could not render it (run: docker compose ${args[*]} config)"
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

status=0
if [[ $# -gt 0 ]]; then
  check_stack "$@" || status=1
else
  base="$repo_root/docker-compose.yml"
  check_stack "$base" || status=1
  shopt -s nullglob
  for overlay in "$repo_root"/docker-compose.*.yml; do
    check_stack "$base" "$overlay" || status=1
  done
fi
exit "$status"
