# Supply chain and runtime hardening

_AOS-622 · proposed decision D-99 · builds on D-91 (layer 6), D-100 (security gate), D-101 (review statuses)._

What runs on every PR, what each check fails on, how containers are locked down, and how `main` is protected.

## 1. Checks on every PR

| Check (job name) | Workflow | What it does | Fails on |
|---|---|---|---|
| `api` | CI | `./gradlew test integrationTest koverVerify` (locked versions + SHA-256 verified artifacts) | any test, coverage below the D-48 gate, an unlisted or altered artifact |
| `web` | CI | `npm ci --ignore-scripts`, typecheck, lint, `test:coverage`, `next build` | any error, coverage below the D-47 gate |
| `go (services/workers)`, `go (tools/sms-reader)` | CI | `go mod verify`, build, vet, test | any error, a module whose hash doesn't match go.sum |
| `pdf-parser` | CI | `pip install --require-hashes`, pytest | any test, any package whose hash isn't in the lock |
| `shell` | CI | shellcheck; exposure check (AOS-616); hardening check; tunnel ingress; backup setup; backups + restore drill end to end (AOS-621) | any failure |
| `osv-scanner` | Security | OSV-Scanner over every lock file (`scripts/security/osv-scan.sh`) | CVSS ≥ 7.0 or no score; lower findings are reported |
| `npm-audit` | Security | `npm audit --omit=dev --audit-level=high` | high/critical in production dependencies |
| `govulncheck (…)` | Security | govulncheck per Go module | a known vulnerability in code we actually call |
| `images` | Security | builds all five images; Trivy (`scripts/security/trivy-scan.sh`); hardened-stack smoke test | any CRITICAL (fixed or not) or an embedded secret; HIGH is reported. Smoke: a service not healthy, writable root, root user, or broken segmentation |
| `codeql (java-kotlin | javascript-typescript | go | python)` | CodeQL | `security-extended` queries | analysis errors; new high/critical alerts fail GitHub's "Code scanning results" check |
| `GitGuardian Security Checks` | GitHub app | secret scanning on every commit | any secret |

Plus the review statuses `review/code` and `review/security` (D-101), set by the reviewer on the head commit.

Security and CodeQL also run **weekly** on `main`, because new advisories appear without code changes.

**Exceptions** are explicit, dated and reasoned, never silent:
- `osv-scanner.toml` (`[[IgnoredVulns]]` with `ignoreUntil` and `reason`);
- `.trivyignore.yaml` (`statement` and `expired_at`).
After the date the finding fails again.

**CI secrets:** the workflows use no secrets at all:
- `permissions: contents: read`; CodeQL also gets `security-events: write`;
- `actions/checkout` with `persist-credentials: false`;
- `pull_request` triggers, never `pull_request_target`, so PR code can't run with write access;
- every action is pinned by commit SHA (with the version in a comment), and Dependabot moves them.
Nothing can leak into logs because nothing secret is present.

## 2. Pinning

| What | How | Updated by |
|---|---|---|
| Base images (5 Dockerfiles) and compose images | `tag@sha256:digest` | Dependabot (`docker`, `docker-compose`) |
| Gradle | `gradle.lockfile` (every resolved version) + `gradle/verification-metadata.xml` (SHA-256 of every artifact, plugins included) | Dependabot `gradle`; then regenerate (see `services/api/build.gradle.kts`) |
| npm | exact versions + `package-lock.json`; CI installs with `--ignore-scripts` | Dependabot `npm` |
| Go | `go.mod` / `go.sum`, `go mod verify` | Dependabot `gomod` |
| Python | `requirements*.in` → hash-locked `requirements*.txt` (pip-tools, `services/pdf-parser/lock.sh`), installed with `--require-hashes` | Dependabot `pip` |
| GitHub Actions | commit SHA | Dependabot `github-actions` |

## 3. Runtime hardening (compose)

Checked by `scripts/check-compose-hardening.sh` (CI `shell`) and proven by `scripts/tests/hardened-stack.smoke.sh` (CI `images`).

- **Every service** has:
  - `read_only: true`, with tmpfs or volumes only where it must write;
  - `cap_drop: [ALL]` and `no-new-privileges`;
  - `mem_limit`, `cpus` and `pids_limit`;
  - a healthcheck.
- **Non-root everywhere.**
  - Our images set `USER`.
  - Postgres runs as uid 70 and Redis as uid 999, so no root entrypoint and no capabilities.
  - The documented exception is Tailscale's userspace `containerboot`: root, but with no capabilities and no tun device.
- **No package manager at run time.** npm/npx/corepack are removed from the web image, and pip/setuptools from the parser image. The web image ships production dependencies only.
- **Networks** (D-99):
  - `backend`: Postgres, Redis, the API, the workers, the backup job.
  - `edge`: the web app and the API (plus the Tailscale and cloudflared sidecars).
  - `parser` (internal, no internet): the API and the statement parser.
  - So the two components that handle untrusted input, the web app (browsers) and the parser (uploaded files), can't reach Postgres or Redis, and the parser can't reach the internet.
- **Ports:** published on `127.0.0.1` only (AOS-616).

**Exceptions** (named in the hardening check):
- the two Go workers have no healthcheck yet: no health endpoint (follow-up);
- cloudflared (distroless) has no probe tool.

## 4. Branch protection for `main` (to apply after Pragun approves)

GitHub → Settings → Branches → **Add branch ruleset** (or classic rule) for `main`:

1. **Require a pull request before merging:** on. Required approvals: 0 (single owner; the review statuses are the review). Dismiss stale approvals: on.
2. **Require status checks to pass:** on, and **require branches to be up to date before merging**: on. Add exactly these (they appear after the workflows have run once on a PR):
   - `api`, `web`, `go (services/workers)`, `go (tools/sms-reader)`, `pdf-parser`, `shell`
   - `osv-scanner`, `npm-audit`, `govulncheck (services/workers)`, `govulncheck (tools/sms-reader)`, `images`
   - `codeql (java-kotlin)`, `codeql (javascript-typescript)`, `codeql (go)`, `codeql (python)`
   - `GitGuardian Security Checks`
   - `review/code`, `review/security` (commit statuses, D-101)

   Keep this list identical to `.github/required-checks.txt`, which `scripts/merge-pr.sh` reads from `main`.
3. **Do not allow bypassing** / **Include administrators:** on.
4. **Allow force pushes:** off. **Allow deletions:** off.
5. **Require linear history:** optional (merge commits are used today; leave off).
6. **Settings → Code security:**
   - turn on **Secret scanning**, **Push protection**, **Dependabot alerts** and **Dependabot security updates**;
   - turn on **Code scanning** (CodeQL, via the workflow);
   - set the code scanning merge protection to fail on **High or higher** security alerts.

Same rule as an API call (classic protection; run by Pragun):
```bash
gh api -X PUT repos/pragunb02/adminos/branches/main/protection --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "api", "web", "go (services/workers)", "go (tools/sms-reader)", "pdf-parser", "shell",
      "osv-scanner", "npm-audit", "govulncheck (services/workers)", "govulncheck (tools/sms-reader)", "images",
      "codeql (java-kotlin)", "codeql (javascript-typescript)", "codeql (go)", "codeql (python)",
      "GitGuardian Security Checks", "review/code", "review/security"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": { "required_approving_review_count": 0, "dismiss_stale_reviews": true },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON
gh api -X PATCH repos/pragunb02/adminos --input - <<'JSON'
{ "security_and_analysis": {
    "secret_scanning": { "status": "enabled" },
    "secret_scanning_push_protection": { "status": "enabled" },
    "dependabot_security_updates": { "status": "enabled" } } }
JSON
gh api -X PUT repos/pragunb02/adminos/vulnerability-alerts
```

### How `scripts/merge-pr.sh` relates
- It merges only when **every** check run on the head commit is `success`.
- Every name in **main's** `.github/required-checks.txt` must have reported. A check that hasn't started yet is waited for, so GitGuardian finishing first can't trigger a merge.
- Both review statuses must be `success` and created by the owner (D-101).
- The merge is pinned to the reviewed SHA.
- It reads the list from `main` through the API, so a PR can't remove its own checks.
- Branch protection enforces the same list on GitHub's side, even for a merge done by hand.

## 5. Known findings, reported not blocking (2026-10-18)
- `pdf-parser` image:
  - 44 HIGH in Debian 13 base packages (util-linux, ncurses, systemd libs, perl-base). None has a fix yet.
  - Trivy reports them weekly; a smaller base image (distroless or Alpine Python) is a follow-up.
- `braces` 3.0.3 (dev-only, no fixed release): dated exception until 2026-12-31.
- Redis has no password. With the segmentation above only the API, workers and backup can reach it. Adding `requirepass` needs a worker change: the Go worker parses only host:port and logs `REDIS_URL` in full. That's a follow-up once PR #79 lands.
