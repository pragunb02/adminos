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
net="aos-bkp-e2e-$id"; s3="aos-bkp-s3-$id"; src="aos-bkp-src-$id"; state="aos-bkp-state-$id"
work="$(mktemp -d)"; chmod 700 "$work"
cleanup() {
  docker rm -f "$s3" "$src" >/dev/null 2>&1 || true
  docker volume rm "$state" >/dev/null 2>&1 || true
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

# ── restore drill ──
drill="$repo_root/scripts/backup/restore-drill.sh"
if out="$("$drill" --latest < "$work/identity" 2>&1)"; then ok "restore drill passes on the latest daily"; else no "drill latest" "$out"; fi
if [[ "$out" == *"4 tables, 5005 rows, all counts match"* ]]; then ok "drill checked every table and row"; else no "drill summary" "$out"; fi
if [[ "$out" != *SEED-PLAINTEXT-MARKER* && "$out" != *AGE-SECRET-KEY* ]]; then ok "drill output carries no data or key"; else no "drill leak" "$out"; fi
if out="$("$drill" --latest monthly < "$work/identity" 2>&1)"; then ok "restore drill passes on the latest monthly"; else no "drill monthly" "$out"; fi
if out="$("$drill" --key "$dump_key" --identity-file "$work/identity" 2>&1)"; then ok "restore drill by key with an identity file"; else no "drill by key" "$out"; fi

in_net age-keygen > "$work/other-identity" 2>/dev/null
if out="$("$drill" --latest < "$work/other-identity" 2>&1)"; then no "a wrong identity must fail"
elif [[ "$out" == *"cannot decrypt"* ]]; then ok "a wrong identity fails"; else no "wrong identity message" "$out"; fi

if out="$("$drill" --key "daily/../../etc/passwd" < "$work/identity" 2>&1)"; then no "a path-trick key must fail"
elif [[ "$out" == *"not a backup key"* ]]; then ok "a path-trick key is refused"; else no "path trick message" "$out"; fi

if out="$("$drill" --latest < /dev/null 2>&1)"; then no "an empty identity must fail"; else ok "an empty identity fails"; fi

# Tampering (S3Mock allows overwrites; on R2 the bucket lock forbids them).
latest="$(list_keys | grep -E '^daily/.*\.dump\.age$' | sort | tail -n 1)"
printf 'not a backup' > "$work/garbage"
docker run --rm --network "$net" -v "$work:/w:ro" --entrypoint curl "$image" -sS --fail -o /dev/null -T /w/garbage "$endpoint/$bucket/$latest"
if out="$("$drill" --key "$latest" < "$work/identity" 2>&1)"; then no "a corrupted dump must fail"
elif [[ "$out" == *"checksum mismatch"* ]]; then ok "a corrupted dump fails the checksum"; else no "corruption message" "$out"; fi

# A forged manifest (anyone with the public key can encrypt one) with wrong counts fails the count check.
first_base="${dump_key%.dump.age}"
real_sha="$(cut -d' ' -f1 < <(s3_curl "$endpoint/$bucket/$first_base.dump.age.sha256"))"
jq -n --arg sha "$real_sha" '{format: 1, createdAt: "x", dumpSha256: $sha, dumpBytes: 0, serverVersion: "18",
  tables: {"public.accounts": 3, "public.transactions": 4999, "ledger.\"Weird Name\"": 2, "public.empty_table": 0}}' > "$work/forged.json"
docker run --rm --network none -v "$work:/w" --user "$(id -u):$(id -g)" --entrypoint age "$image" -r "$BACKUP_AGE_RECIPIENT" -o /w/forged.age /w/forged.json
docker run --rm --network "$net" -v "$work:/w:ro" --entrypoint curl "$image" -sS --fail -o /dev/null -T /w/forged.age "$endpoint/$bucket/$first_base.manifest.json.age"
if out="$("$drill" --key "$dump_key" < "$work/identity" 2>&1)"; then no "wrong row counts must fail"
elif [[ "$out" == *"row counts differ"* && "$out" == *"public.transactions"* ]]; then ok "a row-count mismatch fails and names the table"; else no "count mismatch message" "$out"; fi

echo "# $pass passed, $fail failed"
[[ $fail -eq 0 ]]
