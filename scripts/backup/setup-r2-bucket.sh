#!/usr/bin/env bash
# Creates and locks down the R2 bucket for AdminOS backups (AOS-621). Run once, by the owner, on the Mac.
#
#   npx wrangler@4.151.1 login                          # once, in a browser
#   scripts/backup/setup-r2-bucket.sh adminos-backups   # or --dry-run to print the commands only
#
# The bucket is separate from the documents vault (D-60). It ends up:
#   - private: no r2.dev URL, no custom domain (checked at the end);
#   - locked: objects under daily/ can't be deleted or overwritten for BACKUP_DAILY_KEEP_DAYS (30),
#     monthly/ for BACKUP_MONTHLY_KEEP_MONTHS (12 → 365 days), even with a valid token;
#   - self-cleaning: lifecycle rules delete each object one day after its lock ends, and abort stale uploads.
# Retention numbers come from services/backup/bin/lib.sh, the same code the backup job uses.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../../services/backup/bin/lib.sh
source "$repo_root/services/backup/bin/lib.sh"

wrangler_version="4.151.1"
dry_run=false
if [[ "${1:-}" == "--dry-run" ]]; then dry_run=true; shift; fi
bucket="${1:-}"
[[ -n "$bucket" ]] || { echo "usage: $0 [--dry-run] <bucket-name>" >&2; exit 2; }
is_bucket_name "$bucket" || die "not a valid bucket name: $bucket"

run() {
  echo "+ wrangler $*"
  if [[ "$dry_run" == false ]]; then npx -y "wrangler@$wrangler_version" "$@"; fi
}

require_retention   # whole numbers within bounds, same rules as the backup job
daily_lock="$(retention_days daily)"; monthly_lock="$(retention_days monthly)"
daily_expire="$(expire_days daily)"; monthly_expire="$(expire_days monthly)"

run r2 bucket create "$bucket"
run r2 bucket dev-url disable "$bucket" --force
run r2 bucket lock add "$bucket" lock-daily daily/ --retention-days "$daily_lock" --force
run r2 bucket lock add "$bucket" lock-monthly monthly/ --retention-days "$monthly_lock" --force
run r2 bucket lifecycle add "$bucket" expire-daily daily/ --expire-days "$daily_expire" --force
run r2 bucket lifecycle add "$bucket" expire-monthly monthly/ --expire-days "$monthly_expire" --force
run r2 bucket lifecycle add "$bucket" abort-stale-uploads "" --abort-multipart-days 1 --force
run r2 bucket lock list "$bucket"
run r2 bucket lifecycle list "$bucket"

if [[ "$dry_run" == false ]]; then
  status="$(npx -y "wrangler@$wrangler_version" r2 bucket dev-url get "$bucket" 2>&1)"
  if grep -qi 'enabled' <<<"$status" && ! grep -qi 'disabled' <<<"$status"; then
    die "the r2.dev public URL is ENABLED for $bucket; disable it (Settings → Public access) and rerun"
  fi
  echo "Custom domains on $bucket (there must be none):"
  npx -y "wrangler@$wrangler_version" r2 bucket domain list "$bucket" 2>&1 | sed 's/^/  /' || true
fi

cat <<EOF

Next (Cloudflare dashboard → R2 → Manage API tokens), see docs/guides/BACKUPS.md §2:
  1. Backup token:  "Object Read & Write", Apply to specific buckets only → $bucket. Goes in .env on the host.
  2. Restore token: "Object Read only",    Apply to specific buckets only → $bucket. Password manager only.
Daily kept $daily_lock days (deleted after $daily_expire); monthly kept $monthly_lock days (deleted after $monthly_expire).
EOF
