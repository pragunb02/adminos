#!/usr/bin/env bash
# Tests scripts/backup/setup-r2-bucket.sh --dry-run (AOS-621): the bucket is made private, locked and
# self-cleaning with the same retention the backup job uses (30 daily, 12 monthly).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
setup="$here/../backup/setup-r2-bucket.sh"

pass=0; fail=0
has() { if grep -qF -- "$2" <<<"$out"; then pass=$((pass + 1)); echo "ok   - $1"; else fail=$((fail + 1)); echo "FAIL - $1: missing [$2]"; fi; }
lacks() { if grep -qF -- "$2" <<<"$out"; then fail=$((fail + 1)); echo "FAIL - $1: found [$2]"; else pass=$((pass + 1)); echo "ok   - $1"; fi; }

out="$("$setup" --dry-run adminos-backups)"
has "public r2.dev URL disabled" "r2 bucket dev-url disable adminos-backups"
lacks "public access never enabled" "dev-url enable"
lacks "no custom domain added" "domain add"
has "daily objects locked 30 days" "lock add adminos-backups lock-daily daily/ --retention-days 30"
has "monthly objects locked 365 days" "lock add adminos-backups lock-monthly monthly/ --retention-days 365"
has "daily objects deleted after 31 days" "lifecycle add adminos-backups expire-daily daily/ --expire-days 31"
has "monthly objects deleted after 366 days" "lifecycle add adminos-backups expire-monthly monthly/ --expire-days 366"
has "stale multipart uploads aborted" "--abort-multipart-days 1"
has "restore token is read-only" '"Object Read only"'

out="$(BACKUP_DAILY_KEEP_DAYS=7 "$setup" --dry-run adminos-backups)"
has "retention follows the shared setting" "lock-daily daily/ --retention-days 7"

rc=0; "$setup" --dry-run 'Bad/Name' >/dev/null 2>&1 || rc=$?
if [[ $rc -ne 0 ]]; then pass=$((pass + 1)); echo "ok   - an invalid bucket name is refused"; else fail=$((fail + 1)); echo "FAIL - invalid bucket accepted"; fi
rc=0; "$setup" --dry-run >/dev/null 2>&1 || rc=$?
if [[ $rc -ne 0 ]]; then pass=$((pass + 1)); echo "ok   - a missing bucket name is refused"; else fail=$((fail + 1)); echo "FAIL - missing bucket accepted"; fi

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
