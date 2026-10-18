#!/usr/bin/env bash
# Fails if a container or image loses its runtime hardening (AOS-622, proposed D-99).
#
# Usage:
#   scripts/check-compose-hardening.sh                    # every stack (base, base + each overlay) and every Dockerfile
#   scripts/check-compose-hardening.sh a.yml [b.yml]      # one stack only
#
# Every service, in every profile, must have:
#   read_only: true · cap_drop: [ALL] · security_opt: no-new-privileges · mem_limit · cpus · pids_limit ·
#   a healthcheck · an image pinned by digest (when it isn't built here) · a non-root user (when it isn't
#   built here; our Dockerfiles are checked for USER instead).
# Every Dockerfile: each FROM pinned by digest, and the final stage sets a non-root USER.
# Exceptions are named below with their reason; anything else fails.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/.." && pwd)"
# shellcheck source=lib/compose-render.sh
source "$here/lib/compose-render.sh"

command -v docker >/dev/null || { echo "check-compose-hardening: docker is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "check-compose-hardening: jq is required" >&2; exit 2; }

# exception <service> <rule>: prints the reason when a service is allowed to skip a rule.
exception() {
  case "$1:$2" in
    gmail-worker:healthcheck|agent-runner:healthcheck) echo "no health endpoint yet (follow-up); restart policy covers crashes" ;;
    cloudflared:healthcheck) echo "distroless image: no shell or HTTP client to probe its metrics endpoint" ;;
    cloudflared:user) echo "the image already runs as its nonroot user (65532)" ;;
    tailscale:user) echo "containerboot runs as root in userspace mode, with every capability dropped and no tun device" ;;
    ollama:user) echo "writes its root-owned model volume at /root/.ollama; no capabilities, read-only root, no port, only the API as a peer" ;;
    *) return 1 ;;
  esac
}

# Prints "<service> <rule>" for each failed rule in the rendered compose JSON on stdin.
failed_rules() {
  jq -r '
    (.services // {}) | to_entries[] | .key as $n | .value as $s |
    [
      (select($s.read_only != true) | "read_only"),
      (select((($s.cap_drop // []) | index("ALL")) == null) | "cap_drop"),
      (select((($s.security_opt // []) | map(select(test("^no-new-privileges(:true|=true)?$"))) | length) == 0) | "no_new_privileges"),
      (select(($s.mem_limit // "") == "" and ($s.deploy.resources.limits.memory // "") == "") | "mem_limit"),
      (select(($s.cpus // "") == "" and ($s.deploy.resources.limits.cpus // "") == "") | "cpus"),
      (select(($s.pids_limit // "") == "" and ($s.deploy.resources.limits.pids // "") == "") | "pids_limit"),
      (select(($s.healthcheck // null) == null or $s.healthcheck.disable == true) | "healthcheck"),
      (select($s.build == null and (($s.image // "") | test("@sha256:[0-9a-f]{64}$") | not)) | "digest"),
      (select($s.build == null and (($s.user // "") as $u | $u == "" or $u == "root" or $u == "0" or ($u | test("^0:")))) | "user")
    ][] | "\($n) \(.)"
  '
}

check_stack() {
  local label="$*" rendered svc rule status=0 out=""
  if ! rendered="$(render_stack "$@")"; then
    echo "FAIL $label: docker compose could not render it"
    return 1
  fi
  while read -r svc rule; do
    [[ -z "$svc" ]] && continue
    if exception "$svc" "$rule" >/dev/null; then continue; fi   # a named, reasoned exception
    out+="  - $svc: missing $rule"$'\n'
    status=1
  done < <(failed_rules <<<"$rendered")
  if [[ $status -ne 0 ]]; then printf 'FAIL %s:\n%s' "$label" "$out"; else echo "OK   $label: every service hardened"; fi
  return "$status"
}

# check_dockerfile <path>: every FROM pinned by digest (stage aliases aside); last stage has a non-root USER.
check_dockerfile() {
  local file="$1" status=0 stages="" line ref alias last_user=""
  while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*FROM[[:space:]]+(--platform=[^[:space:]]+[[:space:]]+)?([^[:space:]]+)([[:space:]]+[Aa][Ss][[:space:]]+([^[:space:]]+))? ]]; then
      ref="${BASH_REMATCH[2]}"
      alias="${BASH_REMATCH[4]:-}"     # read before the next =~ overwrites BASH_REMATCH
      last_user=""
      if [[ " $stages " != *" $ref "* && ! "$ref" =~ @sha256:[0-9a-f]{64}$ ]]; then
        echo "FAIL $file: FROM $ref is not pinned by digest"; status=1
      fi
      if [[ -n "$alias" ]]; then stages+=" $alias"; fi
    elif [[ "$line" =~ ^[[:space:]]*USER[[:space:]]+([^[:space:]]+) ]]; then
      last_user="${BASH_REMATCH[1]}"
    fi
  done < "$file"
  if [[ -z "$last_user" || "$last_user" == root || "$last_user" == 0 || "$last_user" == 0:* ]]; then
    echo "FAIL $file: the final stage must set a non-root USER"; status=1
  fi
  [[ $status -eq 0 ]] && echo "OK   $file: pinned, non-root"
  return "$status"
}

status=0
if [[ $# -gt 0 && "$1" == "--dockerfile" ]]; then
  shift
  for f in "$@"; do check_dockerfile "$f" || status=1; done
elif [[ $# -gt 0 ]]; then
  check_stack "$@" || status=1
else
  for_each_stack check_stack || status=1
  while IFS= read -r f; do check_dockerfile "$f" || status=1; done < <(find "$repo_root/services" "$repo_root/apps" -name Dockerfile -not -path '*/node_modules/*' | sort)
fi
exit "$status"
