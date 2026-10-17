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
| A corrupted or altered file | SHA-256 signed into the upload (R2 rejects a body that doesn't match), stored next to the dump and inside the encrypted manifest; the drill refuses a mismatch |
| A backup that silently doesn't restore | Monthly restore drill: real `pg_restore --exit-on-error`, then row counts of every table compared with the manifest |
| Plaintext left on disk | `pg_dump` is piped straight into `age`; the drill pipes `age -d` straight into `pg_restore` in a memory-backed throwaway database |
| Backups stop and nobody notices | The container's healthcheck turns **unhealthy** 26 h after the last success (`docker compose ps`) |

**Honest limit:** R2 has no write-only token. The backup token is "Object Read & Write" scoped to this one
bucket (the narrowest R2 offers). With it, someone could read ciphertext (useless without the key) or add
new objects, but not delete or replace existing ones. Anyone with the public key can also create a fake
backup, so restore a key you chose (the drill prints which one it used).

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
```bash
docker compose -f docker-compose.yml -f docker-compose.backup.yml --profile full up -d
docker compose -f docker-compose.yml -f docker-compose.backup.yml run --rm backup once   # first backup now
docker compose -f docker-compose.yml -f docker-compose.backup.yml ps backup              # "healthy"
```
Use the same `-f` files every time you run compose (or add the overlay to `COMPOSE_FILE` in `.env`:
`COMPOSE_FILE=docker-compose.yml:docker-compose.backup.yml`).

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
Same download and checks, then restore into the new instance's empty database instead of the throwaway one:
1. Bring up a fresh stack with the **same** `TOKEN_ENCRYPTION_KEY` and `JWT_SECRET` (from the password manager)
   but **without starting the API** (`docker compose up -d postgres`).
2. Run the drill once to choose and verify a backup. Then restore that key into the real database with the
   same tool: `restore fetch --key …` and `restore check` from `services/backup` (see `services/backup/bin/restore.sh`),
   with `PGHOST=postgres` on the compose network. `check` refuses a database that isn't empty.
3. Start the rest of the stack; the API's Flyway migrations continue from the restored schema.

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
  `backup once`. A failed night is retried 3 times, 10 minutes apart.
- **`upload failed` with a 403** after setup: the token isn't scoped to this bucket, or a lock rule is
  refusing an overwrite (keys are unique per run, so this means something else wrote the same key).
