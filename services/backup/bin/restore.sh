#!/usr/bin/env bash
# Restore side of AdminOS backups (AOS-621). Driven by scripts/backup/restore-drill.sh; usable by hand.
#
#   restore fetch [--key <…/adminos-<ts>.dump.age> | --latest [daily|monthly]]   → /work, with a READ-ONLY token
#   restore check                                                                   ← age identity on stdin
#
# `check` verifies the SHA-256 of /work/dump.age (against the uploaded .sha256 and the encrypted manifest),
# decrypts with the identity read from stdin (held only in this container's memory-backed /tmp), restores into
# the empty database at PGHOST/PGDATABASE with --exit-on-error, then compares every table's row count with
# the manifest. Any mismatch fails. Only table names and counts are printed, never data.
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

WORK="${RESTORE_WORK_DIR:-/work}"

fetch() {
  require_s3_config
  local key="" tier=daily
  case "${1:-}" in
    --key) key="${2:-}"; [[ -n "$key" ]] || die "--key needs a value" ;;
    --latest) tier="${2:-daily}" ;;
    "") ;;
    *) die "usage: restore fetch [--key KEY | --latest [daily|monthly]]" ;;
  esac
  if [[ -z "$key" ]]; then
    [[ "$tier" == daily || "$tier" == monthly ]] || die "--latest takes daily or monthly"
    key="$(s3_list_keys "$tier/" | latest_dump_key)"
    [[ -n "$key" ]] || die "no backups under $tier/"
  fi
  [[ "$key" =~ ^(daily/[0-9]{4}/[0-9]{2}|monthly)/adminos-[0-9]{8}T[0-9]{6}Z\.dump\.age$ ]] || die "not a backup key: $key"
  local base="${key%.dump.age}"
  s3_get "$key" "$WORK/dump.age"
  s3_get "$base.dump.age.sha256" "$WORK/dump.age.sha256"
  s3_get "$base.manifest.json.age" "$WORK/manifest.json.age"
  printf '%s\n' "$key" > "$WORK/key"
  log "fetched $key"
}

check() {
  local v
  for v in PGHOST PGUSER PGDATABASE PGPASSWORD; do [[ -n "${!v:-}" ]] || die "$v is not set"; done
  local f
  for f in dump.age dump.age.sha256 manifest.json.age; do [[ -s "$WORK/$f" ]] || die "missing $WORK/$f (run fetch first)"; done
  [[ ! -t 0 ]] || die "pipe the age identity (AGE-SECRET-KEY-…) on stdin"

  local tmp identity
  tmp="$(mktemp -d /tmp/restore.XXXXXX)"
  # shellcheck disable=SC2064  # expand now
  trap "rm -rf '$tmp'" EXIT
  identity="$tmp/identity"
  ( umask 077; cat > "$identity" )
  grep -q '^AGE-SECRET-KEY-1' "$identity" || die "stdin is not an age identity"

  local actual expected manifest
  actual="$(sha256sum "$WORK/dump.age" | cut -d' ' -f1)"
  expected="$(cut -d' ' -f1 < "$WORK/dump.age.sha256")"
  [[ "$actual" == "$expected" ]] || die "checksum mismatch: the backup file is corrupted or was altered"

  manifest="$(age --decrypt --identity "$identity" "$WORK/manifest.json.age")" || die "cannot decrypt the manifest (wrong identity?)"
  [[ "$(jq -r '.format' <<<"$manifest")" == "1" ]] || die "unknown manifest format"
  [[ "$(jq -r '.dumpSha256' <<<"$manifest")" == "$actual" ]] || die "the manifest belongs to a different dump"
  log "checksum ok ($actual); manifest from $(jq -r '.createdAt' <<<"$manifest"), Postgres $(jq -r '.serverVersion' <<<"$manifest")"

  [[ "$(psql -X -t -A -c "SELECT count(*) FROM pg_stat_user_tables;")" == "0" ]] || die "the target database is not empty: restore only into a scratch database"

  age --decrypt --identity "$identity" "$WORK/dump.age" |
    pg_restore --exit-on-error --no-owner --no-privileges --single-transaction --dbname "$PGDATABASE" ||
    die "decrypt or pg_restore failed"

  jq -r '.tables | to_entries[] | "\(.key)\t\(.value)"' <<<"$manifest" > "$tmp/expected.tsv"
  psql -X -q -t -A -v ON_ERROR_STOP=1 -c "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;" -c "$COUNT_SQL" -c "COMMIT;" |
    sed '/^$/d' > "$tmp/actual.tsv"

  if ! compare_counts "$tmp/expected.tsv" "$tmp/actual.tsv"; then
    die "row counts differ from the manifest"
  fi
  log "restore ok: $(wc -l < "$tmp/actual.tsv" | tr -d ' ') tables, $(awk -F'\t' '{s += $2} END {print s + 0}' "$tmp/actual.tsv") rows, all counts match"
}

case "${1:-}" in
  fetch) shift; fetch "$@" ;;
  check) check ;;
  *) echo "usage: restore fetch [--key KEY | --latest [daily|monthly]] | restore check" >&2; exit 2 ;;
esac
