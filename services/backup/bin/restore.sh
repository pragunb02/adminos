#!/usr/bin/env bash
# Restore side of AdminOS backups (AOS-621). Driven by scripts/backup/restore-drill.sh; usable by hand
# (docs/guides/BACKUPS.md §3).
#
#   restore fetch [--key <…/adminos-<ts>.dump.age> | --latest [daily|monthly]]   → /work, with a READ-ONLY token
#   restore check                                                                   ← age identity on stdin
#
# fetch: --latest picks the newest dump by the store's LastModified (set by R2, not by the uploader) and skips any
#   key whose timestamp is in the future or far from when it was really uploaded; --key gets the same check.
#   It prints which backup it chose and when it was uploaded.
# check: verifies the SHA-256 of /work/dump.age (against the uploaded .sha256 and the encrypted manifest),
#   requires the manifest's createdAt to equal the key's timestamp (an old backup replayed under a new key
#   fails), decrypts with the identity read from stdin (held only in this container's memory-backed /tmp),
#   lists the dump's contents, restores as a NON-superuser into an EMPTY database with --exit-on-error, then
#   compares every table's row count with the manifest. Any mismatch fails. Only names and counts are printed.
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

WORK="${RESTORE_WORK_DIR:-/work}"
KEY_RE='^(daily/[0-9]{4}/[0-9]{2}|monthly)/adminos-([0-9]{8}T[0-9]{6}Z)\.dump\.age$'

fetch() {
  require_s3_config
  local key="" tier=daily now lm why
  case "${1:-}" in
    --key) key="${2:-}"; [[ -n "$key" ]] || die "--key needs a value" ;;
    --latest) tier="${2:-daily}" ;;
    "") ;;
    *) die "usage: restore fetch [--key KEY | --latest [daily|monthly]]" ;;
  esac
  now="$(date -u +%s)"
  if [[ -z "$key" ]]; then
    [[ "$tier" == daily || "$tier" == monthly ]] || die "--latest takes daily or monthly"
    key="$(s3_list_objects "$tier/" | select_latest_dump "$now")"
    [[ -n "$key" ]] || die "no usable backups under $tier/"
  fi
  [[ "$key" =~ $KEY_RE ]] || die "not a backup key: $key"
  lm="$(s3_last_modified "$key")"
  if ! why="$(key_is_plausible "$key" "$lm" "$now")"; then die "refusing $key: $why"; fi

  local base="${key%.dump.age}"
  s3_get "$key" "$WORK/dump.age"
  s3_get "$base.dump.age.sha256" "$WORK/dump.age.sha256"
  s3_get "$base.manifest.json.age" "$WORK/manifest.json.age"
  printf '%s\n' "$key" > "$WORK/key"
  log "chose $key, uploaded $(date -u -d "@$lm" +%Y-%m-%dT%H:%M:%SZ), $(stat -c %s "$WORK/dump.age") bytes"
}

check() {
  local v f
  for v in PGHOST PGUSER PGDATABASE PGPASSWORD; do [[ -n "${!v:-}" ]] || die "$v is not set"; done
  for f in key dump.age dump.age.sha256 manifest.json.age; do [[ -s "$WORK/$f" ]] || die "missing $WORK/$f (run fetch first)"; done
  [[ ! -t 0 ]] || die "pipe the age identity (AGE-SECRET-KEY-…) on stdin"

  local key ts
  key="$(head -n 1 "$WORK/key")"
  [[ "$key" =~ $KEY_RE ]] || die "not a backup key: $key"
  ts="${BASH_REMATCH[2]}"

  local tmp identity
  tmp="$(mktemp -d /tmp/restore.XXXXXX)"
  # shellcheck disable=SC2064  # expand now
  trap "rm -rf '$tmp'" EXIT
  identity="$tmp/identity"
  ( umask 077; cat > "$identity" )
  grep -q '^AGE-SECRET-KEY-1' "$identity" || die "stdin is not an age identity"

  local actual expected named manifest created
  actual="$(sha256sum "$WORK/dump.age" | cut -d' ' -f1)"
  read -r expected named < "$WORK/dump.age.sha256" || die "unreadable checksum file"
  [[ "$actual" == "$expected" ]] || die "checksum mismatch: the backup file is corrupted or was altered"
  [[ "$named" == "adminos-$ts.dump.age" ]] || die "the checksum file names a different backup ($named)"

  manifest="$(age --decrypt --identity "$identity" "$WORK/manifest.json.age")" || die "cannot decrypt the manifest (wrong identity?)"
  [[ "$(jq -r '.format' <<<"$manifest")" == "1" ]] || die "unknown manifest format"
  [[ "$(jq -r '.dumpSha256' <<<"$manifest")" == "$actual" ]] || die "the manifest belongs to a different dump"
  created="$(jq -r '.createdAt' <<<"$manifest")"
  [[ "$created" == "$ts" ]] || die "the manifest was created at $created, not $ts as the key says: an older backup under a new name"
  log "checksum ok ($actual); manifest from $created, Postgres $(jq -r '.serverVersion' <<<"$manifest")"

  # A dump is SQL that pg_restore runs: never as a superuser, and never over existing data.
  [[ "$(psql -X -t -A -v ON_ERROR_STOP=1 -c "SELECT rolsuper FROM pg_roles WHERE rolname = current_user;")" == "f" ]] ||
    die "connected as a superuser ($PGUSER): restore as a non-superuser role that owns the empty target database"
  [[ "$(psql -X -t -A -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_stat_user_tables;")" == "0" ]] ||
    die "the target database is not empty: restore only into an empty database"

  # What the dump will create, by type, before running any of it. Types AdminOS never uses are refused.
  age --decrypt --identity "$identity" "$WORK/dump.age" | pg_restore --list > "$tmp/toc" || die "cannot read the dump's contents"
  local summary
  summary="$(sed -n 's/^[0-9]*; [0-9]* [0-9]* \([A-Z][A-Z ]*[A-Z]\) .*/\1/p' "$tmp/toc" | sort | uniq -c | awk '{n=$1; $1=""; printf "%s%s %s", sep, n, substr($0,2); sep=", "}')"
  log "dump contains: ${summary:-nothing}"
  if grep -Eq '^[0-9]+; [0-9]+ [0-9]+ (EXTENSION|EVENT TRIGGER|PUBLICATION|SUBSCRIPTION|FOREIGN DATA WRAPPER|SERVER|USER MAPPING|PROCEDURAL LANGUAGE) ' "$tmp/toc"; then
    die "the dump contains object types AdminOS never creates (extension, event trigger, foreign server…): refusing"
  fi

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
