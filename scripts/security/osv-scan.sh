#!/usr/bin/env bash
# Dependency vulnerability scan with OSV-Scanner (AOS-622, proposed D-99), in Docker, read-only.
#
# Scans the lock files that say exactly what ships (not manifests, from which OSV would guess versions):
#   apps/web/package-lock.json · services/api/gradle.lockfile · services/workers/go.mod ·
#   tools/sms-reader/go.mod · services/pdf-parser/requirements.txt and requirements-dev.txt (hash-locked)
#
# Policy: FAIL on any vulnerability with CVSS ≥ 7.0 (high/critical) or with no severity score (fail closed);
# REPORT the rest. Exceptions go in osv-scanner.toml with a reason and an `ignoreUntil` date.
# Usage: scripts/security/osv-scan.sh
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
image="ghcr.io/google/osv-scanner:v2.7.0"
threshold="${OSV_FAIL_CVSS:-7.0}"

lockfiles=(
  apps/web/package-lock.json
  services/api/gradle.lockfile
  services/workers/go.mod
  tools/sms-reader/go.mod
  services/pdf-parser/requirements.txt
  services/pdf-parser/requirements-dev.txt
)
args=()
for f in "${lockfiles[@]}"; do
  [[ -f "$repo_root/$f" ]] || { echo "osv-scan: missing $f" >&2; exit 2; }
  args+=(--lockfile "/repo/$f")
done

rc=0
json="$(docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges:true \
  -v "$repo_root:/repo:ro" -w /repo "$image" \
  scan --config /repo/osv-scanner.toml --format json "${args[@]}" 2>/dev/null)" || rc=$?
# 0 = clean, 1 = vulnerabilities found; anything else is a scanner error (fail closed).
if [[ $rc -ne 0 && $rc -ne 1 ]]; then echo "osv-scan: scanner error (exit $rc)" >&2; exit 2; fi

rows="$(jq -r '
  [.results[]? | (.source.path | sub("^/repo/"; "")) as $file | .packages[]? | .package as $p | .groups[]? |
    {sev: (.max_severity // ""), ids: (.ids | join(",")), pkg: "\($p.ecosystem) \($p.name)@\($p.version)", file: $file}]
  | sort_by(.sev) | reverse | .[] | "\(if .sev == "" then "?" else .sev end)\t\(.pkg)\t\(.ids)\t\(.file)"' <<<"$json")"

if [[ -z "$rows" ]]; then echo "OSV: no known vulnerabilities in ${#lockfiles[@]} lock files"; exit 0; fi

blocking="$(awk -F'\t' -v t="$threshold" '$1 == "?" || $1 + 0 >= t' <<<"$rows")"
echo "OSV findings (CVSS, package, advisories, file):"
sed 's/^/  /' <<<"$rows"
if [[ -n "$blocking" ]]; then
  echo "FAIL: $(wc -l <<<"$blocking" | tr -d ' ') finding(s) with CVSS >= $threshold or no score. Upgrade, or add a dated exception to osv-scanner.toml."
  exit 1
fi
echo "OK: nothing at or above CVSS $threshold (lower findings reported above)"
