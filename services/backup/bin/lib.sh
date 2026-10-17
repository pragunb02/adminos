#!/usr/bin/env bash
# Shared functions for AdminOS encrypted backups (AOS-621, proposed D-98). Sourced by backup.sh and restore.sh.
# Pure functions first (tested in tests/unit.test.sh), then I/O (S3 over curl SigV4, Postgres).
#
# Never print secrets, row data or the S3 response body. Errors name what failed, never a value.
set -euo pipefail

# Retention (the bucket's lock and lifecycle rules come from these too: scripts/backup/setup-r2-bucket.sh).
: "${BACKUP_DAILY_KEEP_DAYS:=30}"
: "${BACKUP_MONTHLY_KEEP_MONTHS:=12}"

log() { printf '%s backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# ── Validation (fail closed) ──

# is_age_recipient <value>: an age X25519 public key (bech32, "age1" + 58 chars). Never an identity (secret key).
is_age_recipient() { [[ "$1" =~ ^age1[02-9ac-hj-np-z]{58}$ ]]; }

# is_bucket_name <value>: S3/R2 bucket naming rules (lowercase, digits, hyphens; 3–63 chars).
is_bucket_name() { [[ "$1" =~ ^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$ ]]; }

# is_allowed_endpoint <url>: https only. Plain http only behind the purpose-named test opt-in.
is_allowed_endpoint() {
  local url="$1"
  [[ "$url" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?/?$ ]] && return 0
  [[ "${BACKUP_TEST_ALLOW_HTTP_ENDPOINT:-}" == "1" && "$url" =~ ^http://[A-Za-z0-9.-]+(:[0-9]+)?/?$ ]] && return 0
  return 1
}

# is_hhmm <value>: 00:00..23:59
is_hhmm() { [[ "$1" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]]; }

# is_credential <value>: what R2/S3 keys look like. Anything with quotes, backslashes, spaces or newlines is
# refused, so a value can never break out of the curl config line it is written into (_curl_s3).
is_credential() { [[ "$1" =~ ^[A-Za-z0-9+/=_.-]{8,255}$ ]]; }

# require_retention: whole numbers within sane bounds (the setup script and the job share these).
require_retention() {
  if ! [[ "$BACKUP_DAILY_KEEP_DAYS" =~ ^[1-9][0-9]{0,3}$ ]] || (( BACKUP_DAILY_KEEP_DAYS > 3650 )); then
    die "BACKUP_DAILY_KEEP_DAYS must be a whole number from 1 to 3650"
  fi
  if ! [[ "$BACKUP_MONTHLY_KEEP_MONTHS" =~ ^[1-9][0-9]{0,2}$ ]] || (( BACKUP_MONTHLY_KEEP_MONTHS > 120 )); then
    die "BACKUP_MONTHLY_KEEP_MONTHS must be a whole number from 1 to 120"
  fi
}

# require_s3_config: endpoint, bucket and credentials are present and well-formed.
require_s3_config() {
  local v
  for v in BACKUP_S3_ENDPOINT BACKUP_S3_BUCKET BACKUP_S3_ACCESS_KEY_ID BACKUP_S3_SECRET_ACCESS_KEY; do
    [[ -n "${!v:-}" ]] || die "$v is not set"
  done
  is_credential "$BACKUP_S3_ACCESS_KEY_ID" || die "BACKUP_S3_ACCESS_KEY_ID has unexpected characters"
  is_credential "$BACKUP_S3_SECRET_ACCESS_KEY" || die "BACKUP_S3_SECRET_ACCESS_KEY has unexpected characters"
  is_allowed_endpoint "$BACKUP_S3_ENDPOINT" || die "BACKUP_S3_ENDPOINT must be https://<host> (got a non-https or malformed URL)"
  is_bucket_name "$BACKUP_S3_BUCKET" || die "BACKUP_S3_BUCKET is not a valid bucket name"
  : "${BACKUP_S3_REGION:=auto}"
  [[ "$BACKUP_S3_REGION" =~ ^[a-z0-9-]+$ ]] || die "BACKUP_S3_REGION is malformed"
}

# require_backup_config: everything a backup run needs.
require_backup_config() {
  require_s3_config
  [[ -n "${BACKUP_AGE_RECIPIENT:-}" ]] || die "BACKUP_AGE_RECIPIENT is not set (the age public key, age1…)"
  if [[ "$BACKUP_AGE_RECIPIENT" == AGE-SECRET-KEY-* ]]; then
    die "BACKUP_AGE_RECIPIENT holds a secret key. Put only the PUBLIC key (age1…) here and keep the secret key offline"
  fi
  is_age_recipient "$BACKUP_AGE_RECIPIENT" || die "BACKUP_AGE_RECIPIENT is not an age public key (age1…)"
  require_retention
  local v
  for v in PGHOST PGUSER PGDATABASE PGPASSWORD; do [[ -n "${!v:-}" ]] || die "$v is not set"; done
}

# ── Naming and retention selection ──

# backup_tiers <yyyy-mm (this run, UTC)> <yyyy-mm of the last successful monthly copy, or "">
# Prints the tiers this run writes: always "daily"; "monthly" when this month has no monthly copy yet,
# so a failed run on the 1st is made up by the next successful run.
backup_tiers() {
  local month="$1" last_monthly="${2:-}"
  [[ "$month" =~ ^[0-9]{4}-(0[1-9]|1[0-2])$ ]] || return 1
  echo daily
  if [[ "$last_monthly" != "$month" ]]; then echo monthly; fi
}

# object_base <tier> <timestamp yyyymmddThhmmssZ>: the key prefix of one backup's objects.
#   daily/2026/10/adminos-20261017T210000Z   monthly/adminos-20261017T210000Z
# Keys are unique per run, so a locked object is never overwritten.
object_base() {
  local tier="$1" ts="$2"
  [[ "$ts" =~ ^([0-9]{4})([0-9]{2})[0-9]{2}T[0-9]{6}Z$ ]] || return 1
  case "$tier" in
    daily)   echo "daily/${BASH_REMATCH[1]}/${BASH_REMATCH[2]}/adminos-$ts" ;;
    monthly) echo "monthly/adminos-$ts" ;;
    *) return 1 ;;
  esac
}

# retention_days <tier>: how long the bucket keeps (locks) a tier's objects.
retention_days() {
  case "$1" in
    daily)   echo "$BACKUP_DAILY_KEEP_DAYS" ;;
    monthly) echo $(( (BACKUP_MONTHLY_KEEP_MONTHS * 365 + 11) / 12 )) ;;   # 12 months → 365 days
    *) return 1 ;;
  esac
}

# expire_days <tier>: lifecycle deletion, one day after the lock ends.
expire_days() { local d; d="$(retention_days "$1")" && echo $(( d + 1 )); }

# seconds_until <HH:MM> <now epoch seconds>: seconds from now to the next HH:MM in $TZ (always 1..86400).
seconds_until() {
  local at="$1" now="$2" today target
  is_hhmm "$at" || return 1
  today="$(date -d "@$now" +%F)"
  target="$(date -d "$today $at" +%s)"
  if (( target <= now )); then target="$(date -d "$today $at next day" +%s)"; fi
  echo $(( target - now ))
}

# is_stale <last success epoch or ""> <started epoch> <now epoch> <max age hours>
# Healthy while the last success (or, before the first one, the start) is younger than max age.
is_stale() {
  local last="$1" started="$2" now="$3" max_h="$4" ref
  ref="${last:-$started}"
  [[ "$ref" =~ ^[0-9]+$ ]] || return 0
  (( now - ref > max_h * 3600 ))
}

# compare_counts <expected tsv> <actual tsv>: "schema.table<TAB>rows" files. Prints differences; 1 if any.
compare_counts() {
  local missing extra
  missing="$(LC_ALL=C comm -23 <(LC_ALL=C sort "$1") <(LC_ALL=C sort "$2"))"
  extra="$(LC_ALL=C comm -13 <(LC_ALL=C sort "$1") <(LC_ALL=C sort "$2"))"
  [[ -z "$missing" && -z "$extra" ]] && return 0
  if [[ -n "$missing" ]]; then printf 'expected: %s\n' "$missing" | sed '2,$s/^/expected: /'; fi
  if [[ -n "$extra" ]]; then printf 'restored: %s\n' "$extra" | sed '2,$s/^/restored: /'; fi
  return 1
}

# ── S3 (SigV4 via curl; the credentials go to curl on stdin, never on the command line) ──

_s3_url() { printf '%s/%s/%s' "${BACKUP_S3_ENDPOINT%/}" "$BACKUP_S3_BUCKET" "$1"; }

_curl_s3() {
  printf 'user = "%s:%s"\n' "$BACKUP_S3_ACCESS_KEY_ID" "$BACKUP_S3_SECRET_ACCESS_KEY" |
    curl --config - --silent --show-error --fail --retry 3 --retry-delay 5 --max-time 1800 \
      --aws-sigv4 "aws:amz:${BACKUP_S3_REGION:-auto}:s3" "$@"
}

# s3_put <file> <key> <sha256 hex>: upload with the SHA-256 as the SigV4 payload hash, so the store rejects a body
# that changed in transit (a checksum, not a signature).
s3_put() {
  local file="$1" key="$2" sha="$3"
  _curl_s3 -o /dev/null -T "$file" \
    -H "x-amz-content-sha256: $sha" -H "x-amz-meta-sha256: $sha" -H "Content-Type: application/octet-stream" \
    "$(_s3_url "$key")" || die "upload failed for $key"
}

# s3_get <key> <out file>
s3_get() { _curl_s3 -o "$2" "$(_s3_url "$1")" || die "download failed for $1"; }

# s3_list_objects <prefix>: "key<TAB>LastModified" for every object under prefix (ListObjectsV2, paginated).
# LastModified is set by the store when the object is written; the uploader can't choose it.
s3_list_objects() {
  local prefix="$1" token="" page q
  while :; do
    q="list-type=2&prefix=$(jq -rn --arg p "$prefix" '$p|@uri')"
    [[ -n "$token" ]] && q+="&continuation-token=$(jq -rn --arg t "$token" '$t|@uri')"
    page="$(_curl_s3 "${BACKUP_S3_ENDPOINT%/}/$BACKUP_S3_BUCKET?$q")" || die "listing $prefix failed"
    parse_list_page <<<"$page"
    if grep -q '<IsTruncated>true</IsTruncated>' <<<"$page"; then
      token="$(grep -o '<NextContinuationToken>[^<]*</NextContinuationToken>' <<<"$page" | sed 's/<[^>]*>//g')"
      [[ -n "$token" ]] || die "listing $prefix: truncated without a continuation token"
    else
      break
    fi
  done
}

# parse_list_page: a ListObjectsV2 XML page on stdin → "key<TAB>LastModified" per <Contents> (any field order).
parse_list_page() {
  tr -d '\r\n' | sed 's#<Contents>#\n<Contents>#g; s#</Contents>#</Contents>\n#g' | grep '^<Contents>' |
    while IFS= read -r entry; do
      local k m
      k="$(grep -o '<Key>[^<]*</Key>' <<<"$entry" | sed 's/<[^>]*>//g')"
      m="$(grep -o '<LastModified>[^<]*</LastModified>' <<<"$entry" | sed 's/<[^>]*>//g')"
      if [[ -n "$k" && -n "$m" ]]; then printf '%s\t%s\n' "$k" "$m"; fi
    done
}

# s3_last_modified <key>: the store's Last-Modified for one object, as epoch seconds.
s3_last_modified() {
  local headers lm
  headers="$(_curl_s3 -o /dev/null -D - -I "$(_s3_url "$1")")" || die "cannot read $1"
  lm="$(grep -i '^last-modified:' <<<"$headers" | head -n 1 | cut -d' ' -f2- | tr -d '\r')"
  [[ -n "$lm" ]] || die "$1 has no Last-Modified"
  date -u -d "$lm" +%s || die "unreadable Last-Modified on $1"
}

# key_epoch <key>: epoch seconds of the UTC timestamp in a backup key (…/adminos-YYYYMMDDTHHMMSSZ.…).
key_epoch() {
  [[ "$1" =~ adminos-([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})Z ]] || return 1
  date -u -d "${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]} ${BASH_REMATCH[4]}:${BASH_REMATCH[5]}:${BASH_REMATCH[6]}" +%s
}

# How far a key's timestamp may be in the future (clock skew), and how far from the moment the store says the
# object was written (a dump takes minutes, not hours). Seconds.
: "${BACKUP_MAX_CLOCK_SKEW_SECONDS:=300}"
: "${BACKUP_MAX_KEY_DRIFT_SECONDS:=3600}"

# key_is_plausible <key> <LastModified epoch> <now epoch>: 0 when the key's timestamp isn't in the future and
# matches when the object was really written; otherwise prints why and returns 1.
key_is_plausible() {
  local key="$1" lm="$2" now="$3" kt drift
  kt="$(key_epoch "$key")" || { echo "no timestamp in the key"; return 1; }
  if (( kt > now + BACKUP_MAX_CLOCK_SKEW_SECONDS )); then echo "its timestamp is in the future"; return 1; fi
  if (( lm > kt )); then drift=$(( lm - kt )); else drift=$(( kt - lm )); fi
  if (( drift > BACKUP_MAX_KEY_DRIFT_SECONDS )); then echo "its timestamp is ${drift}s from when it was uploaded"; return 1; fi
}

# select_latest_dump <now epoch>: reads "key<TAB>LastModified" lines; prints the newest plausible *.dump.age,
# ordered by the store's LastModified, not by the key's own timestamp (which the uploader controls).
# Logs every dump it skips and why.
select_latest_dump() {
  local now="$1" key lm lm_epoch why best="" best_lm=0
  while IFS=$'\t' read -r key lm; do
    [[ "$key" =~ /adminos-[0-9]{8}T[0-9]{6}Z\.dump\.age$ ]] || continue
    if ! lm_epoch="$(date -u -d "$lm" +%s 2>/dev/null)"; then log "skipped $key: unreadable LastModified"; continue; fi
    if ! why="$(key_is_plausible "$key" "$lm_epoch" "$now")"; then log "skipped $key: $why"; continue; fi
    if (( lm_epoch > best_lm )) || [[ $lm_epoch -eq $best_lm && "$key" > "$best" ]]; then
      best="$key"; best_lm="$lm_epoch"
    fi
  done
  if [[ -n "$best" ]]; then printf '%s\n' "$best"; fi
}

# ── Postgres ──

# Row count of every user table, "schema.table<TAB>rows", sorted. Runs in the caller's transaction/snapshot.
# shellcheck disable=SC2034  # used by backup.sh and restore.sh
COUNT_SQL="SELECT format('%I.%I', schemaname, relname) || E'\\t' ||
  (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', schemaname, relname), false, true, '')))[1]::text
  FROM pg_stat_user_tables ORDER BY 1;"
