#!/usr/bin/env bash
# End-to-end test of backups and the restore drill (AOS-621), all in Docker, on an internal test network:
# seeded Postgres 18 → `backup once` → Adobe S3Mock (the API's S3 test double) → scripts/backup/restore-drill.sh.
# Nothing is published to the host. Credentials here are dummies (S3Mock doesn't check them).
# Usage: services/backup/tests/e2e.test.sh   (BACKUP_TEST_IMAGE to reuse a built image)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"
image="${BACKUP_TEST_IMAGE:-adminos-backup:test}"
postgres_image="postgres:18.6-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873"
s3mock_image="adobe/s3mock:4.7.0@sha256:468ba0b0e87f20ad54fbb6085e98c143f5bd1e384a844fa12a58535b4e39d4aa"
[[ -n "${BACKUP_TEST_IMAGE:-}" ]] || docker build -q -t "$image" "$here/.." >/dev/null

id="$(date +%s)-$$"
net="aos-bkp-e2e-$id"; s3="aos-bkp-s3-$id"; src="aos-bkp-src-$id"; state="aos-bkp-state-$id"; fstate="aos-bkp-fstate-$id"; tgt="aos-bkp-tgt-$id"
fakebin=""
work="$(mktemp -d)"; chmod 700 "$work"
cleanup() {
  docker rm -f "$s3" "$src" "$tgt" >/dev/null 2>&1 || true
  docker volume rm "$state" "$fstate" >/dev/null 2>&1 || true
  if [[ -n "$fakebin" ]]; then rm -rf "$fakebin"; fi
  docker network rm "$net" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

pass=0; fail=0
ok() { pass=$((pass + 1)); echo "ok   - $1"; }
no() { fail=$((fail + 1)); echo "FAIL - $1"; [[ -n "${2:-}" ]] && sed 's/^/       /' <<<"$2"; return 0; }

bucket=adminos-backups
endpoint="http://$s3:9090"
export BACKUP_S3_ENDPOINT="$endpoint" BACKUP_S3_BUCKET="$bucket" BACKUP_S3_REGION=auto
export BACKUP_S3_ACCESS_KEY_ID=s3mock-ignores-this BACKUP_S3_SECRET_ACCESS_KEY=s3mock-ignores-this
export BACKUP_RESTORE_ACCESS_KEY_ID=s3mock-ignores-this BACKUP_RESTORE_SECRET_ACCESS_KEY=s3mock-ignores-this
export BACKUP_TEST_ALLOW_HTTP_ENDPOINT=1 BACKUP_DRILL_NETWORK="$net" BACKUP_IMAGE="$image"
PGPASSWORD="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"; export PGPASSWORD POSTGRES_PASSWORD="$PGPASSWORD"

in_net() { docker run --rm --network "$net" --entrypoint "$1" "$image" "${@:2}"; }   # a tool from the image
s3_curl() { in_net curl -sS --fail "$@"; }

echo "# setting up S3Mock and a seeded Postgres"
docker network create --internal "$net" >/dev/null
docker run -d --name "$s3" --network "$net" "$s3mock_image" >/dev/null
docker run -d --name "$src" --network "$net" --tmpfs /var/lib/postgresql \
  -e POSTGRES_PASSWORD -e POSTGRES_USER=adminos -e POSTGRES_DB=adminos "$postgres_image" >/dev/null
for _ in $(seq 1 90); do
  if docker exec "$src" pg_isready -q -h 127.0.0.1 -U adminos -d adminos 2>/dev/null && s3_curl -o /dev/null "$endpoint/" 2>/dev/null; then break; fi
  sleep 1
done
s3_curl -o /dev/null -X PUT "$endpoint/$bucket"

# Realistic shapes: several schemas, a quoted mixed-case name, an empty table, a few thousand rows.
docker exec -i "$src" psql -X -q -v ON_ERROR_STOP=1 -U adminos -d adminos <<'SQL'
CREATE TABLE accounts (id serial PRIMARY KEY, bank text NOT NULL, last4 char(4) NOT NULL);
CREATE TABLE transactions (id bigserial PRIMARY KEY, account_id int REFERENCES accounts(id), paise bigint, narration text);
CREATE SCHEMA ledger;
CREATE TABLE ledger."Weird Name" (k text PRIMARY KEY);
CREATE TABLE empty_table (x int);
INSERT INTO accounts (bank, last4) VALUES ('HDFC', '1234'), ('SBI', '5678'), ('ICICI', '9012');
INSERT INTO transactions (account_id, paise, narration)
  SELECT 1 + (g % 3), g * 100, 'UPI/P2M/SEED-PLAINTEXT-MARKER/' || g FROM generate_series(1, 5000) g;
INSERT INTO ledger."Weird Name" VALUES ('a'), ('b');
SQL

in_net age-keygen > "$work/identity" 2>/dev/null
chmod 600 "$work/identity"
BACKUP_AGE_RECIPIENT="$(grep -o 'age1[0-9a-z]*' "$work/identity" | head -n 1)"; export BACKUP_AGE_RECIPIENT

backup_once() {
  docker run --rm --network "$net" --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
    -v "$state:/state" -e PGHOST="$src" -e PGUSER=adminos -e PGDATABASE=adminos -e PGPASSWORD \
    -e BACKUP_S3_ENDPOINT -e BACKUP_S3_BUCKET -e BACKUP_S3_REGION -e BACKUP_S3_ACCESS_KEY_ID -e BACKUP_S3_SECRET_ACCESS_KEY \
    -e BACKUP_AGE_RECIPIENT -e BACKUP_TEST_ALLOW_HTTP_ENDPOINT "$@" "$image" once 2>&1
}
list_keys() { s3_curl "$endpoint/$bucket?list-type=2" | grep -o '<Key>[^<]*</Key>' | sed 's/<[^>]*>//g' | sort; }

# ── backups ──
if out="$(backup_once)"; then ok "first backup succeeds"; else no "first backup" "$out"; fi
if [[ "$out" == *"uploaded daily/"* && "$out" == *"uploaded monthly/"* ]]; then ok "first backup writes daily and monthly"; else no "tiers" "$out"; fi
if [[ "$out" != *SEED-PLAINTEXT-MARKER* && "$out" != *"$PGPASSWORD"* ]]; then ok "logs carry no row data or password"; else no "log leak" "$out"; fi
keys="$(list_keys)"
if [[ "$(wc -l <<<"$keys" | tr -d ' ')" == 6 ]]; then ok "6 objects: dump, checksum, manifest × 2 tiers"; else no "object count" "$keys"; fi

dump_key="$(grep -E '^daily/.*\.dump\.age$' <<<"$keys" | head -n 1)"
s3_curl "$endpoint/$bucket/$dump_key" > "$work/dump.age"
if head -c 21 "$work/dump.age" | grep -q '^age-encryption.org/v1'; then ok "the stored dump is age ciphertext"; else no "not age format"; fi
if ! grep -aq SEED-PLAINTEXT-MARKER "$work/dump.age"; then ok "no plaintext row data in the stored dump"; else no "plaintext found in the stored object"; fi
s3_curl "$endpoint/$bucket/${dump_key%.dump.age}.manifest.json.age" > "$work/manifest.age"
if ! grep -aq 'transactions' "$work/manifest.age"; then ok "the manifest is encrypted too"; else no "plaintext manifest"; fi

sleep 1   # distinct timestamp
if out="$(backup_once)"; then ok "second backup succeeds"; else no "second backup" "$out"; fi
if [[ "$out" == *"uploaded daily/"* && "$out" != *"uploaded monthly/"* ]]; then ok "second backup in the month writes daily only"; else no "second tiers" "$out"; fi
if [[ "$(list_keys | wc -l | tr -d ' ')" == 9 ]]; then ok "9 objects after two runs"; else no "object count after two runs" "$(list_keys)"; fi

if out="$(backup_once -e BACKUP_TEST_ALLOW_HTTP_ENDPOINT=)"; then no "an http endpoint without the test opt-in must be refused" "$out"
elif [[ "$out" == *"must be https"* ]]; then ok "an http endpoint is refused without the test opt-in"; else no "http refusal message" "$out"; fi
if out="$(backup_once -e "BACKUP_AGE_RECIPIENT=$(grep '^AGE-SECRET-KEY-1' "$work/identity")")"; then no "a secret key as recipient must be refused"
elif [[ "$out" == *"holds a secret key"* && "$out" != *AGE-SECRET-KEY-1* ]]; then ok "a secret key as recipient is refused, not echoed"; else no "secret-key refusal" "$out"; fi

# ── healthcheck and scheduler start-up ──
if out="$(docker run --rm --network none --read-only -v "$state:/state" "$image" healthcheck 2>&1)" && [[ "$out" == healthy ]]; then
  ok "healthcheck is healthy after a successful backup"; else no "healthcheck after success" "$out"; fi
if out="$(docker run --rm --network none --read-only --tmpfs /state "$image" healthcheck 2>&1)"; then no "no state must be unhealthy" "$out"
else ok "healthcheck with no state is unhealthy (fail closed)"; fi
if out="$(docker run --rm --network none --read-only --tmpfs /state -e BACKUP_AT=02:30 "$image" schedule 2>&1)"; then no "a misconfigured scheduler must not start" "$out"
elif [[ "$out" == *"is not set"* ]]; then ok "the scheduler refuses to start misconfigured"; else no "scheduler refusal message" "$out"; fi
if out="$(docker run --rm --network none --read-only --tmpfs /state --tmpfs /tmp -e BACKUP_AT=25:00 "$image" schedule 2>&1)"; then no "a bad BACKUP_AT must fail"
elif [[ "$out" == *"BACKUP_AT must be HH:MM"* ]]; then ok "a malformed BACKUP_AT is refused"; else no "BACKUP_AT message" "$out"; fi

# ── scheduler: a failing step is a failed run (code review B1, D-101) ──
# A fake `jq` that always fails breaks the manifest step, which has no explicit error check. Each attempt must run
# with `set -e` in force: no upload, no last-success, and the attempt is reported as failed.
fakebin="$(mktemp -d)"; chmod 755 "$fakebin"
printf '#!/bin/sh\nexit 1\n' > "$fakebin/jq"; chmod 755 "$fakebin/jq"
before="$(list_keys | wc -l | tr -d ' ')"
out="$(docker run --rm --network "$net" --read-only --tmpfs /tmp -v "$fstate:/state" -v "$fakebin:/fakebin:ro" \
  -e PATH=/fakebin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  -e PGHOST="$src" -e PGUSER=adminos -e PGDATABASE=adminos -e PGPASSWORD \
  -e BACKUP_S3_ENDPOINT -e BACKUP_S3_BUCKET -e BACKUP_S3_REGION -e BACKUP_S3_ACCESS_KEY_ID -e BACKUP_S3_SECRET_ACCESS_KEY \
  -e BACKUP_AGE_RECIPIENT -e BACKUP_TEST_ALLOW_HTTP_ENDPOINT -e BACKUP_RUN_ON_START=true -e BACKUP_ATTEMPTS=1 \
  --entrypoint timeout "$image" 30 /usr/local/bin/backup schedule 2>&1 || true)"
if [[ "$out" == *"attempt 1 of 1 failed"* && "$out" != *"uploaded "* && "$out" != *"done 20"* ]]; then ok "scheduler: a failed step fails the attempt (set -e holds)"
else no "scheduler failure handling" "$out"; fi
if docker run --rm --network none -v "$fstate:/state:ro" --entrypoint test "$image" -e /state/last-success; then no "a failed run recorded last-success"
else ok "scheduler: a failed run records no success"; fi
if [[ "$(list_keys | wc -l | tr -d ' ')" == "$before" ]]; then ok "scheduler: a failed run uploads nothing"; else no "a failed run uploaded objects" "$(list_keys)"; fi
if [[ "$out" == *"encrypting to $BACKUP_AGE_RECIPIENT"* ]]; then ok "scheduler logs the recipient at start-up"; else no "recipient not logged" "$out"; fi

# ── restore drill ──
drill="$repo_root/scripts/backup/restore-drill.sh"
real_latest="$(list_keys | grep -E '^daily/.*\.dump\.age$' | sort | tail -n 1)"
if out="$("$drill" --latest < "$work/identity" 2>&1)"; then ok "restore drill passes on the latest daily"; else no "drill latest" "$out"; fi
if [[ "$out" == *"4 tables, 5005 rows, all counts match"* ]]; then ok "drill checked every table and row"; else no "drill summary" "$out"; fi
if [[ "$out" == *"chose $real_latest, uploaded "* ]]; then ok "drill says which backup it chose and when it was uploaded"; else no "chosen key not printed" "$out"; fi
if [[ "$out" == *"dump contains: "*"TABLE"* ]]; then ok "drill lists the dump's contents before restoring"; else no "contents summary" "$out"; fi
if [[ "$out" != *SEED-PLAINTEXT-MARKER* && "$out" != *AGE-SECRET-KEY* ]]; then ok "drill output carries no data or key"; else no "drill leak" "$out"; fi
if out="$("$drill" --latest monthly < "$work/identity" 2>&1)"; then ok "restore drill passes on the latest monthly"; else no "drill monthly" "$out"; fi
if out="$("$drill" --key "$dump_key" --identity-file "$work/identity" 2>&1)"; then ok "restore drill by key with an identity file"; else no "drill by key" "$out"; fi
if out="$("$drill" --latest --identity-file "$work/identity" 2>&1 < /dev/null)"; then ok "--latest --identity-file works (code review B2)"; else no "--latest --identity-file" "$out"; fi
if out="$("$drill" --latest monthly --identity-file "$work/identity" 2>&1 < /dev/null)"; then ok "--latest monthly --identity-file works"; else no "--latest monthly --identity-file" "$out"; fi
if out="$("$drill" --identity-file "$work/identity" --latest 2>&1 < /dev/null)"; then ok "--identity-file before --latest works"; else no "--identity-file --latest" "$out"; fi

in_net age-keygen > "$work/other-identity" 2>/dev/null
if out="$("$drill" --latest < "$work/other-identity" 2>&1)"; then no "a wrong identity must fail"
elif [[ "$out" == *"cannot decrypt"* ]]; then ok "a wrong identity fails"; else no "wrong identity message" "$out"; fi

if out="$("$drill" --key "daily/../../etc/passwd" < "$work/identity" 2>&1)"; then no "a path-trick key must fail"
elif [[ "$out" == *"not a backup key"* ]]; then ok "a path-trick key is refused"; else no "path trick message" "$out"; fi

if out="$("$drill" --latest < /dev/null 2>&1)"; then no "an empty identity must fail"; else ok "an empty identity fails"; fi

# Restores never run as a superuser: `check` against the source DB as its superuser is refused before anything runs.
fetched="$(mktemp -d)"; chmod 700 "$fetched"
docker run --rm --network "$net" --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" -v "$fetched:/work" \
  -e BACKUP_S3_ENDPOINT -e BACKUP_S3_BUCKET -e BACKUP_S3_REGION -e BACKUP_S3_ACCESS_KEY_ID -e BACKUP_S3_SECRET_ACCESS_KEY \
  -e BACKUP_TEST_ALLOW_HTTP_ENDPOINT --entrypoint /usr/local/bin/restore "$image" fetch --key "$dump_key" >/dev/null 2>&1
if out="$(docker run -i --rm --network "$net" --read-only --tmpfs /tmp --user "$(id -u):$(id -g)" -v "$fetched:/work:ro" \
    -e PGHOST="$src" -e PGUSER=adminos -e PGDATABASE=adminos -e PGPASSWORD \
    --entrypoint /usr/local/bin/restore "$image" check < "$work/identity" 2>&1)"; then no "a superuser restore must be refused" "$out"
elif [[ "$out" == *"connected as a superuser"* ]]; then ok "restore as a superuser is refused"; else no "superuser refusal message" "$out"; fi

# The guide's real-restore procedure (docs/guides/BACKUPS.md §3, code review N2), on a fresh instance shaped like
# compose's (superuser `adminos` owning the empty `adminos` database): a non-superuser role restores, then
# ownership goes back to `adminos` and the role is dropped.
docker run -d --name "$tgt" --network "$net" --tmpfs /var/lib/postgresql \
  -e POSTGRES_PASSWORD -e POSTGRES_USER=adminos -e POSTGRES_DB=adminos "$postgres_image" >/dev/null
for _ in $(seq 1 90); do
  if docker exec "$tgt" pg_isready -q -h 127.0.0.1 -U adminos -d adminos 2>/dev/null; then break; fi
  sleep 1
done
restore_pw="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
docker exec "$tgt" psql -X -q -v ON_ERROR_STOP=1 -U adminos -d postgres \
  -c 'CREATE ROLE adminos_restore LOGIN' -c 'ALTER DATABASE adminos OWNER TO adminos_restore' >/dev/null
# Stand-in for the guide's interactive `\password adminos_restore` (SQL on stdin, not on a command line).
printf "ALTER ROLE adminos_restore PASSWORD '%s';\n" "$restore_pw" | docker exec -i "$tgt" psql -X -q -v ON_ERROR_STOP=1 -U adminos -d postgres >/dev/null
if out="$(PGPASSWORD="$restore_pw" docker run -i --rm --network "$net" --read-only --tmpfs /tmp:size=64m --user "$(id -u):$(id -g)" \
    -v "$fetched:/work:ro" -e PGHOST="$tgt" -e PGUSER=adminos_restore -e PGDATABASE=adminos -e PGPASSWORD \
    --entrypoint /usr/local/bin/restore "$image" check < "$work/identity" 2>&1)"; then ok "real restore: restores as the non-superuser restore role"
else no "real restore check" "$out"; fi
docker exec "$tgt" psql -X -q -v ON_ERROR_STOP=1 -U adminos -d adminos \
  -c 'REASSIGN OWNED BY adminos_restore TO adminos' -c 'ALTER DATABASE adminos OWNER TO adminos' -c 'DROP ROLE adminos_restore' >/dev/null
owners="$(docker exec "$tgt" psql -X -t -A -U adminos -d adminos -c "SELECT count(*) FROM pg_tables WHERE schemaname IN ('public','ledger') AND tableowner <> 'adminos';")"
rows="$(docker exec "$tgt" psql -X -t -A -U adminos -d adminos -c 'SELECT count(*) FROM transactions;')"
if [[ "$owners" == 0 && "$rows" == 5000 ]]; then ok "real restore: data in place, owned by adminos, restore role gone"; else no "real restore ownership/rows (owners=$owners rows=$rows)"; fi
rm -rf "$fetched"

# ── planted backups (security review B1): anyone with the backup token and the public key can upload a
# self-consistent backup. It must never be chosen silently. (S3Mock allows overwrites; R2's lock doesn't.)
put() { docker run --rm --network "$net" --user "$(id -u):$(id -g)" -v "$work:/w:ro" --entrypoint curl "$image" -sS --fail -o /dev/null -T "/w/$1" "$endpoint/$bucket/$2"; }
first_base="${dump_key%.dump.age}"; first_ts="${first_base##*/adminos-}"
s3_curl "$endpoint/$bucket/$first_base.dump.age" > "$work/real.dump"
s3_curl "$endpoint/$bucket/$first_base.manifest.json.age" > "$work/real.manifest"
real_sha="$(cut -d' ' -f1 < <(s3_curl "$endpoint/$bucket/$first_base.dump.age.sha256"))"
plant() {   # plant <base>: the first real backup's objects under a new key, checksum file renamed to match
  printf '%s  %s\n' "$real_sha" "adminos-${1##*/adminos-}.dump.age" > "$work/plant.sha256"
  put plant.sha256 "$1.dump.age.sha256"; put real.manifest "$1.manifest.json.age"; put real.dump "$1.dump.age"
}
future="daily/2099/01/adminos-20990101T000000Z"; plant "$future"
backdated="daily/2026/01/adminos-20260101T000000Z"; plant "$backdated"
if out="$("$drill" --latest < "$work/identity" 2>&1)" && [[ "$out" == *"chose $real_latest"* ]]; then ok "--latest ignores future-dated and backdated plants"; else no "latest with plants" "$out"; fi
if [[ "$out" == *"skipped $future.dump.age: its timestamp is in the future"* && "$out" == *"skipped $backdated.dump.age: its timestamp is"*"from when it was uploaded"* ]]; then
  ok "skipped plants are reported with the reason"; else no "skip messages" "$out"; fi
if out="$("$drill" --key "$future.dump.age" < "$work/identity" 2>&1)"; then no "a future-dated key must be refused"
elif [[ "$out" == *"timestamp is in the future"* ]]; then ok "--key refuses a future-dated backup"; else no "future key message" "$out"; fi
if out="$("$drill" --key "$backdated.dump.age" < "$work/identity" 2>&1)"; then no "a backdated key must be refused"
elif [[ "$out" == *"from when it was uploaded"* ]]; then ok "--key refuses a key whose timestamp doesn't match its upload"; else no "backdated key message" "$out"; fi

# An OLD backup replayed under a NEW, plausible key: the manifest's createdAt gives it away.
sleep 1
replay_ts="$(date -u +%Y%m%dT%H%M%SZ)"; replay="daily/${replay_ts:0:4}/${replay_ts:4:2}/adminos-$replay_ts"; plant "$replay"
if out="$("$drill" --key "$replay.dump.age" < "$work/identity" 2>&1)"; then no "a replayed backup must be refused"
elif [[ "$out" == *"an older backup under a new name"* ]]; then ok "an old backup under a new key fails the createdAt check"; else no "replay message" "$out"; fi
if out="$("$drill" --latest < "$work/identity" 2>&1)"; then no "--latest must not restore the replay" "$out"
elif [[ "$out" == *"chose $replay.dump.age"* && "$out" == *"an older backup under a new name"* ]]; then ok "--latest picks the newest upload but fails closed on the replay"; else no "latest replay" "$out"; fi
# The same replay with its checksum file still naming the old backup.
sleep 1
replay2_ts="$(date -u +%Y%m%dT%H%M%SZ)"; replay2="daily/${replay2_ts:0:4}/${replay2_ts:4:2}/adminos-$replay2_ts"
printf '%s  %s\n' "$real_sha" "adminos-$first_ts.dump.age" > "$work/plant.sha256"
put plant.sha256 "$replay2.dump.age.sha256"; put real.manifest "$replay2.manifest.json.age"; put real.dump "$replay2.dump.age"
if out="$("$drill" --key "$replay2.dump.age" < "$work/identity" 2>&1)"; then no "a mismatched checksum name must fail"
elif [[ "$out" == *"checksum file names a different backup"* ]]; then ok "a checksum file naming another backup fails"; else no "checksum name message" "$out"; fi

# Tampering with a real backup.
printf 'not a backup' > "$work/garbage"
put garbage "$real_latest"
if out="$("$drill" --key "$real_latest" < "$work/identity" 2>&1)"; then no "a corrupted dump must fail"
elif [[ "$out" == *"checksum mismatch"* ]]; then ok "a corrupted dump fails the checksum"; else no "corruption message" "$out"; fi

# A forged manifest (anyone with the public key can encrypt one) with the right checksum and time but wrong counts.
jq -n --arg sha "$real_sha" --arg ts "$first_ts" '{format: 1, createdAt: $ts, dumpSha256: $sha, dumpBytes: 0, serverVersion: "18",
  tables: {"public.accounts": 3, "public.transactions": 4999, "ledger.\"Weird Name\"": 2, "public.empty_table": 0}}' > "$work/forged.json"
docker run --rm --network none -v "$work:/w" --user "$(id -u):$(id -g)" --entrypoint age "$image" -r "$BACKUP_AGE_RECIPIENT" -o /w/forged.age /w/forged.json
put forged.age "$first_base.manifest.json.age"
if out="$("$drill" --key "$dump_key" < "$work/identity" 2>&1)"; then no "wrong row counts must fail"
elif [[ "$out" == *"row counts differ"* && "$out" == *"public.transactions"* ]]; then ok "a row-count mismatch fails and names the table"; else no "count mismatch message" "$out"; fi

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
