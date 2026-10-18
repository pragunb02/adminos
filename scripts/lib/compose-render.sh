#!/usr/bin/env bash
# Shared by scripts/check-compose-*.sh (AOS-616, AOS-622). Source it; don't run it.
#
# render_stack <file> [<file> ...]: prints the stack's rendered config as JSON, every profile included.
# Variables the files require (`${VAR:?}`) get a dummy absolute value only when they're not set in the shell
# or in the .env next to the first file, so checks run in CI without secrets. The output may hold real
# secrets when a .env exists: pipe it to jq, never print it. Returns non-zero if compose can't render it.
set -euo pipefail

render_stack() {
  local file dir var
  local -a args=() dummies=()
  for file in "$@"; do
    [[ -f "$file" ]] || { echo "no such file: $file" >&2; return 1; }
    args+=(-f "$file")
  done
  dir="$(cd "$(dirname "$1")" && pwd)"
  while IFS= read -r var; do
    [[ -z "$var" ]] && continue
    if [[ -n "${!var+x}" ]]; then continue; fi
    if [[ -f "$dir/.env" ]] && grep -Eq "^[[:space:]]*(export[[:space:]]+)?${var}=" "$dir/.env"; then continue; fi
    dummies+=("$var=/compose-check")   # absolute, so it also works as a bind-mount path
  done < <(cat "$@" | grep -oE '\$\{[A-Za-z_][A-Za-z0-9_]*:?\?' | sed -E 's/^\$\{//; s/:?\?$//' | sort -u)
  env ${dummies[@]+"${dummies[@]}"} docker compose "${args[@]}" --profile '*' config --format json 2>/dev/null
}

# for_each_stack <callback>: calls <callback> with the repo's base file alone, then base + each overlay.
for_each_stack() {
  local callback="$1" repo_root overlay status=0
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  "$callback" "$repo_root/docker-compose.yml" || status=1
  shopt -s nullglob
  for overlay in "$repo_root"/docker-compose.*.yml; do
    "$callback" "$repo_root/docker-compose.yml" "$overlay" || status=1
  done
  return "$status"
}
