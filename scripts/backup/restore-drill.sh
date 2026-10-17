#!/usr/bin/env bash
# Restore drill for AdminOS backups (AOS-621). Proves a backup can be restored, without touching real data.
#
#   pbpaste | scripts/backup/restore-drill.sh --latest            # newest daily backup
#   pbpaste | scripts/backup/restore-drill.sh --latest monthly
#   pbpaste | scripts/backup/restore-drill.sh --key daily/2026/10/adminos-20261017T210000Z.dump.age
#   scripts/backup/restore-drill.sh --latest --identity-file /path/to/identity   (prefer stdin)
#
# stdin: the age identity (AGE-SECRET-KEY-1…) from your password manager. It is never written to this
# machine's disk: it goes on stdin into a container whose /tmp is memory-backed.
#
# Environment (a READ-ONLY R2 token, kept offline; not the backup job's token):
#   BACKUP_S3_ENDPOINT, BACKUP_S3_BUCKET, [BACKUP_S3_REGION=auto],
#   BACKUP_RESTORE_ACCESS_KEY_ID, BACKUP_RESTORE_SECRET_ACCESS_KEY
# Optional: BACKUP_IMAGE (default: build services/backup), BACKUP_DRILL_NETWORK (network used to download;
#   default "bridge"; tests use their own).
#
# Steps: download → verify SHA-256 → start a throwaway Postgres 18 on an internal Docker network with no
# published port and memory-backed storage → decrypt and pg_restore into it → compare every table's row
# count with the backup's encrypted manifest → remove everything. Exit 0 only if all of it succeeded.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
postgres_image="postgres:18.6-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873"

die() { echo "restore-drill: $*" >&2; exit 1; }
usage() { sed -n '3,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2; }

select_args=(); identity_file=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --latest)
      # The tier is optional: only "daily" or "monthly" right after --latest is taken as one.
      if [[ "${2:-}" == daily || "${2:-}" == monthly ]]; then select_args=(--latest "$2"); shift
      else select_args=(--latest daily); fi ;;
    --key) [[ -n "${2:-}" ]] || usage; select_args=(--key "$2"); shift ;;
    --identity-file) [[ -n "${2:-}" ]] || usage; identity_file="$2"; shift ;;
    -h|--help) usage ;;
    *) usage ;;
  esac
  shift
done
[[ ${#select_args[@]} -gt 0 ]] || usage

for v in BACKUP_S3_ENDPOINT BACKUP_S3_BUCKET BACKUP_RESTORE_ACCESS_KEY_ID BACKUP_RESTORE_SECRET_ACCESS_KEY; do
  [[ -n "${!v:-}" ]] || die "$v is not set (use the read-only restore token)"
done
if [[ -n "$identity_file" ]]; then
  [[ -r "$identity_file" ]] || die "cannot read $identity_file"
elif [[ -t 0 ]]; then
  die "pipe the age identity on stdin (e.g. pbpaste | $0 --latest), or pass --identity-file"
fi
command -v docker >/dev/null || die "docker is required"

image="${BACKUP_IMAGE:-}"
if [[ -z "$image" ]]; then
  image="adminos-backup:local"
  docker build -q -t "$image" "$repo_root/services/backup" >/dev/null
fi

run_id="$(date +%s)-$$"
net="aos-drill-$run_id"
db="aos-drill-db-$run_id"
work="$(mktemp -d)"
chmod 700 "$work"
cleanup() {
  docker rm -f "$db" >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# Credentials reach containers by name only (-e NAME), never as values on a command line.
export BACKUP_S3_ACCESS_KEY_ID="$BACKUP_RESTORE_ACCESS_KEY_ID" BACKUP_S3_SECRET_ACCESS_KEY="$BACKUP_RESTORE_SECRET_ACCESS_KEY"
export BACKUP_S3_REGION="${BACKUP_S3_REGION:-auto}"
# Two throwaway passwords: the scratch server's superuser (setup only) and the non-superuser the restore runs as.
POSTGRES_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
PGPASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
export POSTGRES_PASSWORD PGPASSWORD
hardening=(--read-only --tmpfs /tmp:size=64m --cap-drop ALL --security-opt no-new-privileges:true --user "$(id -u):$(id -g)")

echo "1/4 downloading ${select_args[*]}"
docker run --rm --network "${BACKUP_DRILL_NETWORK:-bridge}" "${hardening[@]}" -v "$work:/work" \
  -e BACKUP_S3_ENDPOINT -e BACKUP_S3_BUCKET -e BACKUP_S3_REGION -e BACKUP_S3_ACCESS_KEY_ID -e BACKUP_S3_SECRET_ACCESS_KEY \
  -e BACKUP_TEST_ALLOW_HTTP_ENDPOINT --entrypoint /usr/local/bin/restore "$image" fetch "${select_args[@]}"
key="$(cat "$work/key")"

echo "2/4 starting a throwaway Postgres (internal network, no published port)"
docker network create --internal "$net" >/dev/null
docker run -d --name "$db" --network "$net" --tmpfs /var/lib/postgresql:size=4g --security-opt no-new-privileges:true \
  -e POSTGRES_PASSWORD "$postgres_image" >/dev/null
# Over TCP: during init the image runs a temporary server on the Unix socket only, so this sees the final one.
for _ in $(seq 1 90); do
  if docker exec "$db" pg_isready -q -h 127.0.0.1 -U postgres 2>/dev/null; then break; fi
  sleep 1
done
docker exec "$db" pg_isready -q -h 127.0.0.1 -U postgres || die "scratch Postgres did not start"
# The dump is SQL that pg_restore runs, so it runs as a non-superuser that owns only an empty database.
# (SQL on stdin, so the password is never on a command line.)
printf "CREATE ROLE drill_restorer LOGIN PASSWORD '%s';\nCREATE DATABASE drill OWNER drill_restorer;\n" "$PGPASSWORD" |
  docker exec -i "$db" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d postgres >/dev/null || die "could not prepare the scratch database"

echo "3/4 verifying, decrypting and restoring $key"
identity_src=/dev/stdin
[[ -n "$identity_file" ]] && identity_src="$identity_file"
docker run -i --rm --network "$net" "${hardening[@]}" -v "$work:/work:ro" \
  -e PGHOST="$db" -e PGUSER=drill_restorer -e PGDATABASE=drill -e PGPASSWORD \
  --entrypoint /usr/local/bin/restore "$image" check < "$identity_src"

echo "4/4 RESTORE DRILL PASSED: $key"
