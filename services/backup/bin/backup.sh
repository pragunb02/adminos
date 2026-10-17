#!/usr/bin/env bash
# AdminOS encrypted Postgres backups (AOS-621, proposed D-98).
#
#   backup once          one backup now
#   backup schedule      every day at BACKUP_AT (HH:MM in TZ), retrying a failed run; the container's main mode
#   backup healthcheck   unhealthy when the last success (or the start) is older than BACKUP_MAX_AGE_HOURS
#
# One run:
#   1. Open a REPEATABLE READ snapshot; pg_dump (custom format) from that snapshot, piped straight into
#      age for the public key BACKUP_AGE_RECIPIENT. The plaintext dump never touches disk, and this host
#      can't decrypt it (the secret key is offline).
#   2. In the same snapshot, count the rows of every table: the manifest the restore drill checks against.
#   3. SHA-256 of the ciphertext; upload dump, checksum and encrypted manifest with the hash signed (SigV4),
#      under daily/… and, once a month, monthly/…. The bucket's lock rules forbid deleting or overwriting
#      them until retention ends; its lifecycle rules delete them after (scripts/backup/setup-r2-bucket.sh).
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
# shellcheck source=lib.sh
source "$here/lib.sh"

STATE_DIR="${BACKUP_STATE_DIR:-/state}"
SCRATCH_DIR="${BACKUP_SCRATCH_DIR:-/tmp}"

# dump_with_counts <out ciphertext> <out counts tsv>
dump_with_counts() {
  local out="$1" counts="$2" snapshot line
  coproc PSQL { psql -X -q -t -A -v ON_ERROR_STOP=1 2>&1; }
  # shellcheck disable=SC2154  # PSQL / PSQL_PID are set by coproc
  local in_fd="${PSQL[1]}" out_fd="${PSQL[0]}" pid="$PSQL_PID"
  printf '%s\n' "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;" "SELECT pg_export_snapshot();" >&"$in_fd"
  read -r -t 60 snapshot <&"$out_fd" || die "could not open a snapshot (is Postgres reachable?)"
  [[ "$snapshot" =~ ^[0-9A-F]+-[0-9A-F]+-[0-9]+$ ]] || die "could not open a snapshot: unexpected reply from psql"

  pg_dump --snapshot="$snapshot" --format=custom --compress=6 --no-password |
    age --encrypt --recipient "$BACKUP_AGE_RECIPIENT" --output "$out" || die "pg_dump or age failed"

  printf '%s\n' "$COUNT_SQL" "SELECT '__end__';" >&"$in_fd"
  : > "$counts"
  while read -r -t 300 line <&"$out_fd"; do
    [[ "$line" == "__end__" ]] && break
    [[ "$line" =~ ^[^$'\t']+$'\t'[0-9]+$ ]] || die "row counts: unexpected reply from psql"
    printf '%s\n' "$line" >> "$counts"
  done
  [[ "$line" == "__end__" ]] || die "row counts: psql stopped answering"
  printf '%s\n' "COMMIT;" '\q' >&"$in_fd"
  wait "$pid" || die "psql exited with an error"
}

run_once() {
  require_backup_config
  mkdir -p "$STATE_DIR"
  local ts month last_monthly work sha size tables tier base
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  month="$(date -u +%Y-%m)"
  last_monthly="$(cat "$STATE_DIR/last-monthly" 2>/dev/null || true)"

  work="$(mktemp -d "$SCRATCH_DIR/backup.XXXXXX")"
  # shellcheck disable=SC2064  # expand now: the trap must remove this run's directory
  trap "rm -rf '$work'" RETURN EXIT

  log "starting $ts"
  dump_with_counts "$work/dump.age" "$work/counts.tsv"
  sha="$(sha256sum "$work/dump.age" | cut -d' ' -f1)"
  size="$(stat -c %s "$work/dump.age")"
  tables="$(wc -l < "$work/counts.tsv" | tr -d ' ')"
  (( tables > 0 )) || die "the database has no tables: refusing to upload an empty backup"

  jq -n --arg ts "$ts" --arg sha "$sha" --argjson size "$size" --arg server "$(psql -X -t -A -c 'SHOW server_version;')" \
     --rawfile counts "$work/counts.tsv" \
     '{format: 1, createdAt: $ts, dumpSha256: $sha, dumpBytes: $size, serverVersion: $server,
       tables: ($counts | split("\n") | map(select(length > 0) | split("\t") | {key: .[0], value: (.[1] | tonumber)}) | from_entries)}' |
    age --encrypt --recipient "$BACKUP_AGE_RECIPIENT" --output "$work/manifest.json.age"
  printf '%s  %s\n' "$sha" "adminos-$ts.dump.age" > "$work/dump.age.sha256"

  while read -r tier; do
    base="$(object_base "$tier" "$ts")"
    s3_put "$work/dump.age" "$base.dump.age" "$sha"
    s3_put "$work/dump.age.sha256" "$base.dump.age.sha256" "$(sha256sum "$work/dump.age.sha256" | cut -d' ' -f1)"
    s3_put "$work/manifest.json.age" "$base.manifest.json.age" "$(sha256sum "$work/manifest.json.age" | cut -d' ' -f1)"
    log "uploaded $base.dump.age ($size bytes, $tables tables, sha256 $sha)"
    if [[ "$tier" == monthly ]]; then printf '%s\n' "$month" > "$STATE_DIR/last-monthly"; fi
  done < <(backup_tiers "$month" "$last_monthly")

  date -u +%s > "$STATE_DIR/last-success"
  log "done $ts"
}

schedule() {
  : "${BACKUP_AT:=02:30}"
  is_hhmm "$BACKUP_AT" || die "BACKUP_AT must be HH:MM (24 h)"
  require_backup_config          # refuse to start misconfigured, rather than fail at night
  mkdir -p "$STATE_DIR"
  date -u +%s > "$STATE_DIR/started"
  log "scheduled daily at $BACKUP_AT ${TZ:-UTC}"
  if [[ "${BACKUP_RUN_ON_START:-false}" == "true" ]]; then run_with_retries || true; fi
  while :; do
    sleep "$(seconds_until "$BACKUP_AT" "$(date +%s)")"
    run_with_retries || true
  done
}

# Each attempt runs in a subshell, so a failure (die) is retried instead of stopping the scheduler.
run_with_retries() {
  local attempt max="${BACKUP_ATTEMPTS:-3}" delay="${BACKUP_RETRY_DELAY_SECONDS:-600}"
  for (( attempt = 1; attempt <= max; attempt++ )); do
    if ( run_once ); then return 0; fi
    log "attempt $attempt of $max failed"
    (( attempt < max )) && sleep "$delay"
  done
  log "ERROR: backup failed $max times; the healthcheck turns unhealthy after ${BACKUP_MAX_AGE_HOURS:-26} h"
  return 1
}

healthcheck() {
  local last started
  last="$(cat "$STATE_DIR/last-success" 2>/dev/null || true)"
  started="$(cat "$STATE_DIR/started" 2>/dev/null || true)"
  if is_stale "$last" "$started" "$(date -u +%s)" "${BACKUP_MAX_AGE_HOURS:-26}"; then
    echo "unhealthy: no successful backup in ${BACKUP_MAX_AGE_HOURS:-26} h"
    exit 1
  fi
  echo "healthy"
}

case "${1:-}" in
  once) run_once ;;
  schedule) schedule ;;
  healthcheck) healthcheck ;;
  *) echo "usage: backup once|schedule|healthcheck" >&2; exit 2 ;;
esac
