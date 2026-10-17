# AOS-S8.1 — Secure wealth foundation

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E8](AOS-E8.md) |
| **Status** | 🟦 In progress |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 19h |
| **Actual** | 3h |
| **Created / Updated** | 2026-10-17 / 2026-10-17 |
| **Decisions** | D-30, D-59, D-60, D-79 (proposed) |
| **Depends on** | — |

## User story
**As** Pragun, **I want** my wealth data stored encrypted, backed up offsite and audited, **so that** I can put my real holdings in without risking a leak or losing them.

## Acceptance criteria
- [x] A research report with options, evidence and a roadmap (AOS-801)
- [ ] A nightly `pg_dump` encrypted with age (public key on the machine, private key offline) goes to a separate R2 bucket with a write-only token. A monthly restore drill restores into a throw-away Postgres and checks the counts (AOS-803)
- [ ] Wealth schema: investment accounts, instruments, append-only investment transactions, derived lots, prices, FX rates and snapshots, with `Quantity`, `UnitPrice` and `FxRate` types and no `Double` (AOS-802)
- [ ] Identifiers are encrypted with AES-256-GCM + AAD under an HKDF-derived wealth key, looked up by an HMAC blind index, and shown as the last 4 characters only (AOS-802)
- [ ] Audit rows for imports, corrections, policy versions, exports, identifier reveals and backups; re-auth for exports; a "hide amounts" privacy mode (AOS-804)

## Design
See [`WEALTH-RESEARCH.md`](../research/WEALTH-RESEARCH.md) §3.1–§3.2 and §6. It reuses `TokenEncryptor` v2 (D-59) with a separate purpose key.

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-801](AOS-801.md) | Research: wealth design; Epic E8; proposed D-79 | Claude | ✅ | 3h | 3h |
| AOS-802 | Wealth domain model + schema, exact quantity/price/FX types, encrypted identifiers + blind index | Claude | ⬜ | 8h | |
| AOS-803 | Encrypted offsite backups (pg_dump → age → separate R2 bucket), restore drill job, key escrow guide | Claude + Pragun | ⬜ | 5h | |
| AOS-804 | Wealth audit events, re-auth for exports and identifier reveal, privacy mode | Claude | ⬜ | 3h | |

_Task tickets are created as files when this story is picked up._

## Demo / verification
The restore drill restores last night's backup into a fresh container and prints matching counts. The DB shows only ciphertext for identifiers.

## Activity log
- 2026-10-17 — Claude: story created (AOS-801); AOS-801 done.
