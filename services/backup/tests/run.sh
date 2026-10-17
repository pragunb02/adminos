#!/usr/bin/env bash
# Builds the backup image and runs its tests (AOS-621). Needs docker.
#   services/backup/tests/run.sh          unit + end-to-end
#   services/backup/tests/run.sh unit     unit only (no network)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
image="${BACKUP_TEST_IMAGE:-adminos-backup:test}"

docker build -q -t "$image" "$here/.." >/dev/null
docker run --rm --network none --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
  --entrypoint /bin/bash -v "$here:/tests:ro" "$image" /tests/unit.test.sh

if [[ "${1:-all}" == "all" ]]; then
  BACKUP_TEST_IMAGE="$image" "$here/e2e.test.sh"
fi
