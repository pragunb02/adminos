#!/usr/bin/env bash
# Smoke test of the hardened stack (AOS-622): every service starts and turns healthy with a read-only root,
# no capabilities and no-new-privileges, and the network segmentation holds:
#   - the statement parser can't reach Postgres, Redis or the internet;
#   - the web app can't reach Postgres or Redis, but reaches the API.
# Runs under its own project name with throwaway random secrets, publishes NO host ports, and removes
# everything (containers, volumes, networks) at the end.
#
#   scripts/tests/hardened-stack.smoke.sh                       # builds the images with compose
#   SMOKE_IMAGE_PREFIX=aos622 scripts/tests/hardened-stack.smoke.sh   # reuse <prefix>/{api,web,workers,pdf-parser}:scan
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project="aos-smoke-$$"
work="$(mktemp -d)"; chmod 700 "$work"

rand() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }
b64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
cat > "$work/env" <<EOF
DB_PASSWORD=$(rand 24)
JWT_SECRET=$(b64 48)
TOKEN_ENCRYPTION_KEY=$(b64 32)
OWNER_EMAIL=owner@example.com
APP_URL=http://localhost:3000
EOF

# No host ports in the smoke run (it may run next to a real stack), and optionally prebuilt images.
{
  echo "services:"
  for s in postgres redis api-server web-app pdf-parser gmail-worker agent-runner; do
    printf '  %s:\n' "$s"
    case "$s" in postgres|redis|api-server|web-app) echo "    ports: !reset []" ;; esac
    if [[ -n "${SMOKE_IMAGE_PREFIX:-}" ]]; then
      case "$s" in
        api-server) img=api ;; web-app) img=web ;; pdf-parser) img=pdf-parser ;; gmail-worker|agent-runner) img=workers ;; *) img="" ;;
      esac
      if [[ -n "$img" ]]; then printf '    build: !reset null\n    image: %s/%s:scan\n' "$SMOKE_IMAGE_PREFIX" "$img"; fi
    fi
  done
} > "$work/override.yml"

dc() { docker compose -p "$project" --project-directory "$repo_root" -f "$repo_root/docker-compose.yml" -f "$work/override.yml" --env-file "$work/env" --profile full "$@"; }
cleanup() { dc down -v --remove-orphans >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

pass=0; fail=0
ok() { pass=$((pass + 1)); echo "ok   - $1"; }
no() { fail=$((fail + 1)); echo "FAIL - $1"; if [[ -n "${2:-}" ]]; then sed 's/^/       /' <<<"$2"; fi; }

build_flag=(); [[ -z "${SMOKE_IMAGE_PREFIX:-}" ]] && build_flag=(--build)
if out="$(dc up -d ${build_flag[@]+"${build_flag[@]}"} --wait --wait-timeout 240 2>&1)"; then ok "every service starts and is healthy (or running, for the workers)"
else no "stack did not come up" "$out"; dc ps -a; dc logs --tail 30; exit 1; fi

# Each running container really is hardened (inspect, not just config).
while read -r name; do
  [[ -z "$name" ]] && continue
  info="$(docker inspect "$name" --format '{{.HostConfig.ReadonlyRootfs}} {{json .HostConfig.CapDrop}} {{json .HostConfig.SecurityOpt}} {{.Config.User}} {{.HostConfig.Memory}}')"
  if [[ "$info" == "true "*'"ALL"'*"no-new-privileges"* && "$info" != *" 0" ]]; then ok "$name: read-only, caps dropped, no-new-privileges, memory-limited"
  else no "$name hardening" "$info"; fi
done < <(dc ps --format '{{.Name}}')

# Non-root at run time, including the third-party images.
for svc in postgres redis api-server web-app pdf-parser gmail-worker; do
  uid="$(dc exec -T "$svc" id -u 2>/dev/null || true)"
  if [[ "$uid" =~ ^[0-9]+$ && "$uid" != 0 ]]; then ok "$svc runs as uid $uid"; else no "$svc runs as root or id failed ($uid)"; fi
done

probe_py='import socket,sys
h,p=sys.argv[1],int(sys.argv[2])
try:
    socket.create_connection((h,p),timeout=3).close(); print("open")
except Exception as e: print("closed")'
reach_from_parser() { dc exec -T pdf-parser python -c "$probe_py" "$1" "$2" 2>/dev/null || echo error; }
reach_from_web() { dc exec -T web-app node -e "require('net').connect({host:process.argv[1],port:+process.argv[2],timeout:3000}).on('connect',()=>{console.log('open');process.exit(0)}).on('error',()=>{console.log('closed');process.exit(0)}).on('timeout',()=>{console.log('closed');process.exit(0)})" "$1" "$2" 2>/dev/null || echo error; }

for target in "postgres 5432" "redis 6379" "1.1.1.1 443"; do
  # shellcheck disable=SC2086
  r="$(reach_from_parser $target)"
  if [[ "$r" == closed ]]; then ok "parser can't reach $target"; else no "parser reaches $target ($r)"; fi
done
r="$(reach_from_parser api-server 8080)"; if [[ "$r" == open ]]; then ok "parser network still reaches the API side"; else no "api-server not on the parser network ($r)"; fi
for target in "postgres 5432" "redis 6379"; do
  # shellcheck disable=SC2086
  r="$(reach_from_web $target)"
  if [[ "$r" == closed ]]; then ok "web app can't reach $target"; else no "web app reaches $target ($r)"; fi
done
r="$(reach_from_web api-server 8080)"; if [[ "$r" == open ]]; then ok "web app reaches the API"; else no "web app can't reach the API ($r)"; fi

# The root filesystem really is read-only.
if dc exec -T api-server sh -c 'touch /app/x' >/dev/null 2>&1; then no "api-server root filesystem is writable"; else ok "api-server can't write its root filesystem"; fi
if dc exec -T web-app sh -c 'touch /app/x' >/dev/null 2>&1; then no "web-app root filesystem is writable"; else ok "web-app can't write its root filesystem"; fi

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
