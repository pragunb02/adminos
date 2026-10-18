#!/usr/bin/env bash
# OWASP ZAP baseline scan, then the scripted probes, of a LOCAL AdminOS stack (AOS-623, D-150). Never run it
# against anything else.
#
# It starts a throwaway copy of the stack (own project name, random secrets, and NO published host ports), runs
# ZAP's passive baseline (spider + passive rules, no attacks) from a container attached to the stack's internal
# `edge` network against the web app and the API by service name, writes the reports, and removes everything.
# Nothing listens on the host at any point, so the scan can't reach, or be reached from, outside this machine.
#
#   scripts/security/zap-baseline.sh                          # builds the images with compose
#   ZAP_IMAGE_PREFIX=aos622 scripts/security/zap-baseline.sh  # reuse <prefix>/{api,web,workers,pdf-parser}:scan
#   ZAP_REPORT_DIR=/tmp/zap scripts/security/zap-baseline.sh  # where the reports go (default: build/zap)
#
# Exit status: 0 when ZAP reports no FAIL-level alert, 1 when it does, 2 when the stack or ZAP couldn't run.
# The triage of each alert (fix, ticket, or explicit accept) is in docs/security/PENTEST.md.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project="aos-zap-$$"
work="$(mktemp -d)"; chmod 700 "$work"
reports="${ZAP_REPORT_DIR:-$repo_root/build/zap}"
# ZAP, pinned by digest (D-99). Update together with docs/security/PENTEST.md.
zap_image="ghcr.io/zaproxy/zaproxy@sha256:5ccfff9a411743ab2615136a2edd7416bec9ce41572fa94e935df38c14adb2b6"

rand() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }
b64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
cat > "$work/env" <<EOF
DB_PASSWORD=$(rand 24)
REDIS_PASSWORD=$(rand 32)
JWT_SECRET=$(b64 48)
TOKEN_ENCRYPTION_KEY=$(b64 32)
OWNER_EMAIL=owner@example.com
APP_URL=http://localhost:3000
EOF

# No host ports (it may run next to a real stack), and optionally prebuilt images.
{
  echo "services:"
  for s in postgres redis api-server web-app pdf-parser; do
    printf '  %s:\n' "$s"
    case "$s" in postgres|redis|api-server|web-app) echo "    ports: !reset []" ;; esac
    if [[ -n "${ZAP_IMAGE_PREFIX:-}" ]]; then
      case "$s" in api-server) img=api ;; web-app) img=web ;; pdf-parser) img=pdf-parser ;; *) img="" ;; esac
      if [[ -n "$img" ]]; then printf '    build: !reset null\n    image: %s/%s:scan\n' "$ZAP_IMAGE_PREFIX" "$img"; fi
    fi
  done
} > "$work/override.yml"

dc() { docker compose -p "$project" --project-directory "$repo_root" -f "$repo_root/docker-compose.yml" -f "$work/override.yml" --env-file "$work/env" "$@"; }
# Everything goes: containers, volumes, networks and the images built for this run. A container compose failed to
# stop is removed by its project label, so nothing of the scan stack is left running.
cleanup() {
  dc down -v --remove-orphans --rmi local >/dev/null 2>&1 || true
  docker ps -aq --filter "label=com.docker.compose.project=$project" | xargs docker rm -f >/dev/null 2>&1 || true
  docker network ls -q --filter "label=com.docker.compose.project=$project" | xargs docker network rm >/dev/null 2>&1 || true
  docker volume ls -q --filter "label=com.docker.compose.project=$project" | xargs docker volume rm >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# The rendered config must publish nothing, or we refuse to start (belt and braces over the override).
# Fail closed: a config compose can't render is a refusal too.
if ! rendered="$(dc config --format json)"; then echo "refusing: compose could not render the scan stack" >&2; exit 2; fi
if grep -q '"published"' <<<"$rendered"; then echo "refusing: the scan stack would publish a host port" >&2; exit 2; fi

build_flag=(); [[ -z "${ZAP_IMAGE_PREFIX:-}" ]] && build_flag=(--build)
if ! dc up -d ${build_flag[@]+"${build_flag[@]}"} --wait --wait-timeout 240 postgres redis pdf-parser api-server web-app; then
  echo "the stack did not come up" >&2; dc ps -a; dc logs --tail 30; exit 2
fi

mkdir -p "$reports"
# ZAP runs as its own user; it gets a directory of its own (not the one holding the throwaway secrets).
mkdir -p "$work/zap"; chmod 777 "$work/zap"
network="${project}_edge"
status=0
for target in "web http://web-app:3000/" "api http://api-server:8080/health"; do
  name="${target%% *}"; url="${target#* }"
  # The ZAP container: no published ports, only its own report directory mounted, on the stack's internal network.
  set +e
  docker run --rm --network "$network" -v "$work/zap:/zap/wrk:rw" "$zap_image" \
    zap-baseline.py -t "$url" -m 2 -I -j \
      -r "zap-$name.html" -J "zap-$name.json" -w "zap-$name.md"
  code=$?
  set -e
  cp "$work/zap/zap-$name."{html,json,md} "$reports/" 2>/dev/null || true
  # zap-baseline.py: 0 clean, 1 FAIL alerts, 2 WARN only (with -I warnings don't fail), 3 ZAP error.
  case "$code" in
    0|2) echo "ok   - $name: no FAIL-level alerts" ;;
    1) echo "FAIL - $name: FAIL-level alerts (see $reports/zap-$name.md)"; status=1 ;;
    *) echo "ERROR - $name: ZAP exited $code"; status=2 ;;
  esac
done
# Scripted black-box probes (docs/security/PENTEST.md) from the same network, while the stack is up.
if ! "$repo_root/scripts/security/pentest-probes.sh" "$network" | tee "$reports/probes.txt"; then status=1; fi
echo "Reports: $reports"
exit "$status"
