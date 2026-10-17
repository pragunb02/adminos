#!/usr/bin/env bash
# Container image scan with Trivy (AOS-622, proposed D-99). Images must already be built locally.
#   scripts/security/trivy-scan.sh adminos/api:ci adminos/web:ci …
#
# Policy: FAIL on any CRITICAL vulnerability (OS packages and app libraries, fixed or not: an unfixed critical
# needs a decision, recorded in .trivyignore.yaml with a reason and an expiry); REPORT HIGH. Secrets found in an
# image also fail. Trivy reads the local images through the Docker socket, read-only.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
image="aquasec/trivy:0.76.0"
[[ $# -gt 0 ]] || { echo "usage: $0 <image> [<image> ...]" >&2; exit 2; }

trivy() {
  docker run --rm --cap-drop ALL --security-opt no-new-privileges:true \
    -v /var/run/docker.sock:/var/run/docker.sock:ro -v aos-trivy-cache:/root/.cache/trivy \
    -v "$repo_root/.trivyignore.yaml:/trivyignore.yaml:ro" "$image" "$@"
}

status=0
for target in "$@"; do
  echo "== $target"
  # Report: HIGH and CRITICAL, as a table (never fails by itself).
  trivy image --quiet --scanners vuln --severity HIGH,CRITICAL --ignorefile /trivyignore.yaml \
    --format table --exit-code 0 "$target"
  # Gate: CRITICAL vulnerabilities or any embedded secret.
  if ! trivy image --quiet --scanners vuln,secret --severity CRITICAL --ignorefile /trivyignore.yaml \
       --format table --exit-code 1 "$target" >/dev/null; then
    echo "FAIL $target: CRITICAL vulnerability or embedded secret (details above / rerun with --scanners secret)"
    status=1
  else
    echo "OK   $target: no CRITICAL vulnerabilities, no secrets"
  fi
done
exit "$status"
