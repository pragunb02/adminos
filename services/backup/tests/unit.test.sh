#!/usr/bin/env bash
# Unit tests for services/backup/bin/lib.sh (AOS-621). Pure functions only; no network, no database.
# Runs inside the backup image (GNU date, bash):
#   docker build -t adminos-backup:test services/backup
#   docker run --rm --network none --entrypoint bash -v "$PWD/services/backup/tests:/tests:ro" adminos-backup:test /tests/unit.test.sh
set -euo pipefail
# shellcheck source=../bin/lib.sh
source /usr/local/lib/adminos-backup/lib.sh

pass=0; fail=0
ok() { pass=$((pass + 1)); echo "ok   - $1"; }
no() { fail=$((fail + 1)); echo "FAIL - $1"; }
eq() { if [[ "$2" == "$3" ]]; then ok "$1"; else no "$1: expected [$3], got [$2]"; fi; }
yes_() { if "${@:2}"; then ok "$1"; else no "$1"; fi; }
not_() { if "${@:2}"; then no "$1"; else ok "$1"; fi; }

# A syntactically valid age public key, built here (58 bech32 characters after "age1").
recipient="age1$(printf 'q%.0s' {1..58})"

# ── validation ──
yes_ "a well-formed age recipient is accepted" is_age_recipient "$recipient"
not_ "an age secret key is not a recipient" is_age_recipient "AGE-SECRET-KEY-1$(printf 'Q%.0s' {1..58})"
not_ "a recipient with a non-bech32 character (b) is refused" is_age_recipient "age1$(printf 'b%.0s' {1..58})"
not_ "a truncated recipient is refused" is_age_recipient "age1qqqq"
not_ "an ssh key is refused" is_age_recipient "ssh-ed25519 AAAA"
yes_ "a bucket name is accepted" is_bucket_name "adminos-backups"
not_ "an uppercase bucket is refused" is_bucket_name "AdminOS"
not_ "a bucket with a slash is refused" is_bucket_name "a/b"
not_ "a bucket with a path trick is refused" is_bucket_name "../x"
yes_ "an https endpoint is accepted" is_allowed_endpoint "https://0123456789abcdef.r2.cloudflarestorage.com"
not_ "an http endpoint is refused by default" is_allowed_endpoint "http://s3mock:9090"
not_ "an endpoint with a path is refused" is_allowed_endpoint "https://x.example/bucket"
not_ "an endpoint with credentials is refused" is_allowed_endpoint "https://someone@x.example"
not_ "an endpoint with a query is refused" is_allowed_endpoint "https://x.example?a=b"
yes_ "http is allowed only behind the purpose-named test opt-in" env BACKUP_TEST_ALLOW_HTTP_ENDPOINT=1 bash -c 'source /usr/local/lib/adminos-backup/lib.sh; is_allowed_endpoint http://s3mock:9090'
yes_ "HH:MM is accepted" is_hhmm "02:30"
not_ "24:00 is refused" is_hhmm "24:00"
not_ "2:30 is refused" is_hhmm "2:30"

# require_backup_config fails closed (runs in a subshell: die exits).
cfg_out() {
  env -i PATH="$PATH" BACKUP_S3_ENDPOINT=https://x.example BACKUP_S3_BUCKET=adminos-backups \
    BACKUP_S3_ACCESS_KEY_ID=placeholder-id BACKUP_S3_SECRET_ACCESS_KEY=placeholder-value BACKUP_AGE_RECIPIENT="$recipient" \
    PGHOST=db PGUSER=u PGDATABASE=d PGPASSWORD=p "$@" bash -c 'source /usr/local/lib/adminos-backup/lib.sh; require_backup_config' 2>&1
}
cfg() { cfg_out "$@" >/dev/null; }
yes_ "a complete config passes" cfg
for v in BACKUP_S3_ENDPOINT BACKUP_S3_BUCKET BACKUP_S3_ACCESS_KEY_ID BACKUP_S3_SECRET_ACCESS_KEY BACKUP_AGE_RECIPIENT PGHOST PGPASSWORD; do
  not_ "missing $v stops the run" cfg "$v="
done
out="$(cfg_out BACKUP_AGE_RECIPIENT="AGE-SECRET-KEY-1ABC" || true)"
if [[ "$out" == *"holds a secret key"* && "$out" != *"AGE-SECRET-KEY-1ABC"* ]]; then ok "a secret key as recipient is refused without echoing it"; else no "secret key recipient: $out"; fi
out="$(cfg_out BACKUP_S3_SECRET_ACCESS_KEY= || true)"
if [[ "$out" == *"BACKUP_S3_SECRET_ACCESS_KEY is not set"* ]]; then ok "errors name the variable"; else no "error text: $out"; fi
not_ "a zero retention is refused" cfg BACKUP_DAILY_KEEP_DAYS=0
not_ "a daily retention above 10 years is refused" cfg BACKUP_DAILY_KEEP_DAYS=3651
not_ "a monthly retention above 120 is refused" cfg BACKUP_MONTHLY_KEEP_MONTHS=121
not_ "a non-numeric retention is refused" cfg BACKUP_DAILY_KEEP_DAYS=1e3
not_ "a negative retention is refused" cfg BACKUP_MONTHLY_KEEP_MONTHS=-1
yes_ "the largest allowed retention passes" cfg BACKUP_DAILY_KEEP_DAYS=3650 BACKUP_MONTHLY_KEEP_MONTHS=120
yes_ "an R2-style credential is accepted" is_credential "0123456789abcdef0123456789abcdef"
not_ "a credential with a double quote is refused (curl config injection)" is_credential 'abcdefgh"x'
not_ "a credential with a backslash is refused" is_credential 'abcdefgh\x'
not_ "a credential with a newline is refused" is_credential $'abcdefgh\nurl = "https://evil.example"'
not_ "a credential with a space is refused" is_credential "abcd efgh"
not_ "a credential with an unexpected character is refused by the config" cfg 'BACKUP_S3_SECRET_ACCESS_KEY=abcdefgh"x'
not_ "an http endpoint is refused" cfg BACKUP_S3_ENDPOINT=http://x.example

# ── retention selection ──
eq "first run ever writes daily and monthly" "$(backup_tiers 2026-10 "" | tr '\n' ' ')" "daily monthly "
eq "a later run in the same month writes daily only" "$(backup_tiers 2026-10 2026-10 | tr '\n' ' ')" "daily "
eq "the first run of a new month writes monthly" "$(backup_tiers 2026-11 2026-10 | tr '\n' ' ')" "daily monthly "
eq "a missed 1st is made up by the next run" "$(backup_tiers 2026-11 2026-09 | tr '\n' ' ')" "daily monthly "
not_ "a malformed month is refused" backup_tiers 2026-13 ""
eq "daily key" "$(object_base daily 20261017T210000Z)" "daily/2026/10/adminos-20261017T210000Z"
eq "monthly key" "$(object_base monthly 20261101T210000Z)" "monthly/adminos-20261101T210000Z"
not_ "an unknown tier is refused" object_base weekly 20261017T210000Z
not_ "a malformed timestamp is refused" object_base daily "2026-10-17"
not_ "a timestamp with a path trick is refused" object_base daily "../../x"
eq "30 daily backups kept" "$(retention_days daily)" "30"
eq "12 monthly backups kept (365 days)" "$(retention_days monthly)" "365"
eq "daily expire one day after the lock" "$(expire_days daily)" "31"
eq "monthly expire one day after the lock" "$(expire_days monthly)" "366"
eq "retention follows the settings" "$(BACKUP_DAILY_KEEP_DAYS=7 retention_days daily)" "7"

# ── choosing a backup (security review B1, D-101) ──
eq "key timestamp to epoch" "$(key_epoch daily/2026/10/adminos-20261017T210000Z.dump.age)" "$(date -u -d '2026-10-17 21:00:00' +%s)"
not_ "a key without a timestamp has no epoch" key_epoch daily/notes.txt
now="$(date -u -d '2026-10-18 03:00:00' +%s)"
k_ok="daily/2026/10/adminos-20261018T023000Z.dump.age"; lm_ok="$(date -u -d '2026-10-18 02:31:10' +%s)"
yes_ "a key uploaded a minute after its timestamp is plausible" key_is_plausible "$k_ok" "$lm_ok" "$now"
k_future="daily/2099/01/adminos-20990101T000000Z.dump.age"
eq "a future-dated key is refused" "$(key_is_plausible "$k_future" "$now" "$now" || true)" "its timestamp is in the future"
k_old="daily/2026/01/adminos-20260101T000000Z.dump.age"
out="$(key_is_plausible "$k_old" "$now" "$now" || true)"
if [[ "$out" == *"from when it was uploaded"* ]]; then ok "a key whose timestamp is far from its upload time is refused"; else no "drift: $out"; fi
yes_ "a key a minute in the future is within clock skew" key_is_plausible "daily/2026/10/adminos-20261018T030100Z.dump.age" "$now" "$now"

listing=$'daily/2026/10/adminos-20261017T023000Z.dump.age	2026-10-17T02:31:05.000Z
daily/2026/10/adminos-20261017T023000Z.manifest.json.age	2026-10-17T02:31:04.000Z
daily/2026/10/adminos-20261018T023000Z.dump.age	2026-10-18T02:31:10.000Z
daily/2099/01/adminos-20990101T000000Z.dump.age	2026-10-18T02:50:00.000Z
daily/2026/01/adminos-20260101T000000Z.dump.age	2026-10-18T02:55:00.000Z
daily/2026/10/notes.txt	2026-10-18T02:59:00.000Z'
eq "latest = newest plausible upload, not the biggest key" "$(select_latest_dump "$now" <<<"$listing" 2>/dev/null)" "$k_ok"
out="$(select_latest_dump "$now" <<<"$listing" 2>&1 >/dev/null)"
if [[ "$out" == *"skipped daily/2099/01/adminos-20990101T000000Z.dump.age: its timestamp is in the future"* && "$out" == *"skipped daily/2026/01/adminos-20260101T000000Z.dump.age"* ]]; then
  ok "skipped keys are logged with the reason"; else no "skip log: $out"; fi
eq "only implausible dumps → nothing chosen" "$(select_latest_dump "$now" <<<"$k_future"$'\t2026-10-18T02:50:00Z' 2>/dev/null || true)" ""
eq "an unreadable LastModified is skipped" "$(select_latest_dump "$now" <<<"$k_ok"$'\tnot-a-date' 2>/dev/null || true)" ""

xml='<?xml version="1.0"?><ListBucketResult><Name>b</Name><Contents><LastModified>2026-10-18T02:31:10.000Z</LastModified><Key>daily/2026/10/adminos-20261018T023000Z.dump.age</Key><Size>5</Size></Contents><Contents><Key>daily/2026/10/x.txt</Key><LastModified>2026-10-18T02:00:00.000Z</LastModified></Contents><IsTruncated>false</IsTruncated></ListBucketResult>'
eq "a listing page parses in any field order" "$(parse_list_page <<<"$xml")" $'daily/2026/10/adminos-20261018T023000Z.dump.age\t2026-10-18T02:31:10.000Z\ndaily/2026/10/x.txt\t2026-10-18T02:00:00.000Z'
eq "an empty listing parses to nothing" "$(parse_list_page <<<'<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>')" ""

# ── scheduling ──
# 2026-10-17 01:00 in Asia/Kolkata → 02:30 the same day is 90 minutes away.
now="$(TZ=Asia/Kolkata date -d '2026-10-17 01:00' +%s)"
eq "next run later today" "$(TZ=Asia/Kolkata seconds_until 02:30 "$now")" "5400"
now="$(TZ=Asia/Kolkata date -d '2026-10-17 02:30' +%s)"
eq "exactly at the time → tomorrow, never 0" "$(TZ=Asia/Kolkata seconds_until 02:30 "$now")" "86400"
now="$(TZ=Asia/Kolkata date -d '2026-10-17 23:00' +%s)"
eq "after the time → tomorrow" "$(TZ=Asia/Kolkata seconds_until 02:30 "$now")" "12600"
not_ "a malformed time is refused" seconds_until "2:3" 0

# ── healthcheck ──
not_ "fresh success is healthy" is_stale 1000 0 2000 26
yes_ "success older than max age is stale" is_stale 1000 0 $((1000 + 27 * 3600)) 26
not_ "before the first run, a recent start is healthy" is_stale "" 1000 2000 26
yes_ "before the first run, an old start is stale" is_stale "" 1000 $((1000 + 27 * 3600)) 26
yes_ "no state at all is stale (fail closed)" is_stale "" "" 2000 26
yes_ "garbage state is stale (fail closed)" is_stale "x" "" 2000 26

# ── restore comparison ──
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
printf 'public.a\t3\npublic.b\t0\n' > "$tmp/e"; printf 'public.b\t0\npublic.a\t3\n' > "$tmp/same"
printf 'public.a\t2\npublic.b\t0\n' > "$tmp/fewer"; printf 'public.a\t3\n' > "$tmp/missing"
cc_quiet() { compare_counts "$@" >/dev/null; }
yes_ "same counts in any order match" cc_quiet "$tmp/e" "$tmp/same"
not_ "a missing row fails" cc_quiet "$tmp/e" "$tmp/fewer"
not_ "a missing table fails" cc_quiet "$tmp/e" "$tmp/missing"
eq "differences are reported by table" "$(compare_counts "$tmp/e" "$tmp/fewer" || true)" $'expected: public.a\t3\nrestored: public.a\t2'

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
