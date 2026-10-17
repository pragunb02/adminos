# Encrypted backups, restore drill and key backup

_AOS-621 · proposed decision D-98 · builds on D-91 (data layer), D-60 (R2), D-59/D-60 (`TOKEN_ENCRYPTION_KEY`)._

**What you get:** every night at 02:30 (your `SCHEDULER_ZONE`) the `backup` container dumps Postgres,
encrypts it **on this machine** with [age](https://age-encryption.org), and uploads it to a **separate,
private, locked** Cloudflare R2 bucket. 30 daily and 12 monthly copies are kept. A scripted **restore drill**
proves a backup restores, by restoring it into a throwaway database and checking every table's row count.

**Time:** about 30 minutes once, then a 5-minute drill every month.

---

## 1. How it's protected

| Threat | What stops it |
|---|---|
| Someone reads the bucket (leaked token, Cloudflare breach) | Only ciphertext is stored. The host has only the age **public** key; the secret key is offline |
| Ransomware or an attacker on the host deletes or overwrites backups | R2 **bucket lock** rules: nothing under `daily/` can be deleted or overwritten for 30 days, `monthly/` for 365, even with a valid token |
| The bucket is made public by mistake | Setup turns the `r2.dev` URL off and adds no custom domain; the bucket is separate from the documents vault |
| A corrupted or altered file | SHA-256 of each file sent as the upload's SigV4 payload hash (R2 rejects a body that changed in transit; a checksum, not a signature), stored next to the dump and inside the encrypted manifest; the drill refuses a mismatch |
| A planted backup (someone with the backup token and the public key uploads a fake one) | Restores choose by R2's own upload time, skip keys dated in the future or far from their upload time, require the manifest's time to equal the key's, print which backup was chosen, and restore as a non-superuser into an empty database |
| A backup that silently doesn't restore | Monthly restore drill: real `pg_restore --exit-on-error`, then row counts of every table compared with the manifest |
| Plaintext left on disk | `pg_dump` is piped straight into `age`; the drill pipes `age -d` straight into `pg_restore` in a memory-backed throwaway database |
| Backups stop and nobody notices | The container's healthcheck turns **unhealthy** 26 h after the last success (`docker compose ps`) |

**Honest limit:** R2 has no write-only token. The backup token is "Object Read & Write" scoped to this one
bucket (the narrowest R2 offers). With it, someone could read ciphertext (useless without the key) or add
new objects, but not delete or replace existing ones. Anyone with the token and the public key can also upload a
fake backup that looks valid and is then locked. The defences above stop the tricks we know (future dates,
backdating, replaying an old backup under a new name), but a fake uploaded *right now* with a correct timestamp
can't be told apart by the tooling. So **read what the drill says it chose** (`chose daily/…, uploaded …`) and,
for a real restore, pick the key yourself (`--key`) from a time you trust.

## 2. Set up (once)

### 2a. The encryption key (on your Mac, not the server)
```bash
brew install age
age-keygen -o adminos-backup.key      # prints: Public key: age1…
```
- Put the **whole file content** (the `AGE-SECRET-KEY-1…` line) in your password manager as
  "AdminOS backup key". Then delete the file: `rm -P adminos-backup.key`.
- Put **only the public key** (`age1…`) in `.env` as `BACKUP_AGE_RECIPIENT=`. The backup job refuses a
  secret key there.

### 2b. The bucket
```bash
npx wrangler@4.151.1 login                              # once, in a browser
scripts/backup/setup-r2-bucket.sh adminos-backups       # --dry-run first to see what it will do
```
It creates the bucket, turns public access off, adds the lock rules (30 days `daily/`, 365 days `monthly/`)
and lifecycle rules (delete one day after the lock ends; abort stale uploads), and lists them back. Don't reuse
the documents bucket (D-60).

### 2c. Two tokens (Cloudflare dashboard → R2 → Manage API tokens → Create API token)
1. **Backup token:** permission **Object Read & Write**, *Apply to specific buckets only* → `adminos-backups`,
   no other bucket. Copy the Access Key ID and Secret Access Key into `.env`:
   ```bash
   BACKUP_S3_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
   BACKUP_S3_BUCKET=adminos-backups
   BACKUP_S3_ACCESS_KEY_ID=
   BACKUP_S3_SECRET_ACCESS_KEY=
   ```
2. **Restore token:** permission **Object Read only**, same single bucket. Store it **only in your password
   manager** ("AdminOS restore token"). It is used for drills and real restores, never left on the host.

### 2d. Start it
Settings (all optional, in `.env`): `BACKUP_AT` (02:30, in `SCHEDULER_ZONE`), `BACKUP_RUN_ON_START` (`false`;
`true` also backs up when the container starts), `BACKUP_ATTEMPTS` (3 attempts in total per night),
`BACKUP_RETRY_DELAY_SECONDS` (600), `BACKUP_MAX_AGE_HOURS` (26: the healthcheck turns unhealthy when the last
success is older).
```bash
docker compose -f docker-compose.yml -f docker-compose.backup.yml --profile full up -d
docker compose -f docker-compose.yml -f docker-compose.backup.yml run --rm backup once   # first backup now
docker compose -f docker-compose.yml -f docker-compose.backup.yml ps backup              # "healthy"
```
Use the same `-f` files every time you run compose (or add the overlay to `COMPOSE_FILE` in `.env`:
`COMPOSE_FILE=docker-compose.yml:docker-compose.backup.yml`).

**Check the recipient.** The log starts with `scheduled daily at 02:30 …; encrypting to age1…`
(`docker compose … logs backup | head`). Compare that `age1…` with the public key next to your secret key in
the password manager (`age-keygen -y` on the secret key prints it). If they differ, every backup is being
encrypted for someone else: stop the container and fix `BACKUP_AGE_RECIPIENT`.

## 3. Restore drill (monthly, and before go-live)

Off the server, on your Mac with Docker running:
```bash
export BACKUP_S3_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com BACKUP_S3_BUCKET=adminos-backups
read -rs BACKUP_RESTORE_ACCESS_KEY_ID && export BACKUP_RESTORE_ACCESS_KEY_ID          # paste, Enter
read -rs BACKUP_RESTORE_SECRET_ACCESS_KEY && export BACKUP_RESTORE_SECRET_ACCESS_KEY  # paste, Enter
# Copy the "AdminOS backup key" from the password manager, then:
pbpaste | scripts/backup/restore-drill.sh --latest            # or: --latest monthly, or --key daily/…dump.age
pbcopy < /dev/null                                            # clear the clipboard
```
Expected last line: `4/4 RESTORE DRILL PASSED: daily/…`, after `restore ok: N tables, M rows, all counts match`.
Any checksum, decryption, restore or count problem fails the drill with a reason, and nothing is left behind
(the throwaway database is in memory on an internal Docker network with no published port).

Record the date and result in the AOS-621 ticket's Activity log (or PROGRESS.md).

### A real restore (disaster)
A dump is SQL that `pg_restore` runs, so it runs as a **non-superuser** role that owns only the empty
database (`check` refuses a superuser such as `adminos`, and refuses a database that isn't empty). These steps
are the ones the end-to-end test runs.

```bash
# 0. A fresh stack: the SAME DB_PASSWORD, TOKEN_ENCRYPTION_KEY and JWT_SECRET in .env (from the password manager),
#    and the backup overlay's settings. Use the overlay with every command below:
export COMPOSE_FILE=docker-compose.yml:docker-compose.backup.yml
docker compose up -d postgres                     # only Postgres: the API must not create tables first

# 1. Choose and verify the backup with a drill (prints "chose …, uploaded …"); note the key you want.
pbpaste | scripts/backup/restore-drill.sh --latest

# 2. A restore role that owns the empty `adminos` database; type its password when asked.
docker compose exec postgres psql -U adminos -d postgres \
  -c 'CREATE ROLE adminos_restore LOGIN' -c 'ALTER DATABASE adminos OWNER TO adminos_restore'
docker compose exec postgres psql -U adminos -d postgres -c '\password adminos_restore'

# 3. Download that key with the READ-ONLY restore token (from the password manager, not .env).
read -rs BACKUP_S3_ACCESS_KEY_ID && export BACKUP_S3_ACCESS_KEY_ID
read -rs BACKUP_S3_SECRET_ACCESS_KEY && export BACKUP_S3_SECRET_ACCESS_KEY
work="$(mktemp -d)" && chmod 700 "$work"
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" -v "$work:/work" \
  -e BACKUP_S3_ACCESS_KEY_ID -e BACKUP_S3_SECRET_ACCESS_KEY \
  --entrypoint /usr/local/bin/restore backup fetch --key daily/2026/10/adminos-20261017T210000Z.dump.age

# 4. Verify and restore as adminos_restore. The identity goes on stdin into the container's memory-backed /tmp
#    (the service is read-only with a tmpfs /tmp, so it never lands on disk).
read -rs PGPASSWORD && export PGPASSWORD          # the adminos_restore password from step 2
pbpaste | docker compose run --rm --no-deps -T --user "$(id -u):$(id -g)" -v "$work:/work:ro" \
  -e PGUSER=adminos_restore -e PGPASSWORD --entrypoint /usr/local/bin/restore backup check
pbcopy < /dev/null; unset PGPASSWORD BACKUP_S3_ACCESS_KEY_ID BACKUP_S3_SECRET_ACCESS_KEY; rm -rf "$work"

# 5. Hand everything back to adminos and remove the restore role.
docker compose exec postgres psql -U adminos -d adminos -c 'REASSIGN OWNED BY adminos_restore TO adminos' \
  -c 'ALTER DATABASE adminos OWNER TO adminos' -c 'DROP ROLE adminos_restore'

# 6. Start everything; Flyway continues from the restored schema history.
docker compose --profile full up -d
```

## 4. Keys you must keep offline (password manager, plus one offline copy)

| Secret | Why it matters | Where |
|---|---|---|
| **age backup key** (`AGE-SECRET-KEY-1…`) | Without it every backup is unreadable, forever | Password manager + offline copy. Never on the server, never in the bucket |
| **`TOKEN_ENCRYPTION_KEY`** | Decrypts Gmail tokens and wraps every documents-vault file key (D-60). A restored DB without it has unreadable vault files and tokens | Password manager + offline copy. Also in `.env` on the host |
| **`JWT_SECRET`** | Signs sessions. Losing it only signs everyone out, but keep it to avoid that | Password manager. Also in `.env` |
| `DB_PASSWORD` | Needed to connect to the restored database | Password manager. Also in `.env` |
| R2 **restore** token | Read-only access for drills and restores | Password manager only |
| Tailscale Tailnet Lock disablement secrets (AOS-616) | Recovery if all trusted devices are lost | Password manager + offline copy |

- **Offline copy:** print the age key and `TOKEN_ENCRYPTION_KEY` (or write them on paper) and keep them
  somewhere safe at home, sealed. Check it once a year.
- **Never** put any of these in the repo, in the backup bucket, in a chat, or in an email.
- If the age key leaks: generate a new one, switch `BACKUP_AGE_RECIPIENT`, run `backup once`, and treat older
  backups as readable by whoever has the old key until their lock expires.
- If the backup token leaks: delete it in the R2 dashboard and create a new one (backups stay locked).

## 5. Disk encryption (check once, at go-live)

The host holds the live database and `.env`. On the Mac:
```bash
fdesetup status        # must print "FileVault is On."
```
If it's off: System Settings → Privacy & Security → FileVault → Turn On. Store the recovery key in your
password manager (not iCloud if you prefer it offline). Docker Desktop's disk image lives inside your home
folder, so FileVault covers the database volume too.

On a VPS later: use the provider's encrypted volumes, and keep the same rule that keys come from the
password manager, not from the provider's metadata or snapshots. Disable provider snapshots of the data
volume, or make sure they are encrypted and private.

## 6. Troubleshooting
- `docker compose … logs backup` shows each run: key, size, table count and checksum (never data).
- **Unhealthy:** look for `ERROR:` lines (e.g. `upload failed`, `could not open a snapshot`). Fix the cause, then run
  `backup once`. A failed night gets 3 attempts in total, 10 minutes apart (`BACKUP_ATTEMPTS`,
  `BACKUP_RETRY_DELAY_SECONDS`); every step of an attempt must succeed, or nothing is recorded as a success.
- **`--latest` skipped a key** (`skipped …: its timestamp is in the future` / `… from when it was uploaded`):
  something uploaded an object whose name doesn't match when it was written. Treat it as tampering: rotate the
  backup token and check the R2 audit log.
- **`upload failed` with a 403** after setup: the token isn't scoped to this bucket, or a lock rule is
  refusing an overwrite (keys are unique per run, so this means something else wrote the same key).
