# AOS-<nnn> — <Task title>

| Field | Value |
|---|---|
| **Type** | Task |
| **Parent** | AOS-S<n>.<m> (Epic AOS-E<n>) |
| **Status** | ⬜ Todo / 🟦 In progress / ⛔ Blocked / 🟨 Done, unverified / ✅ Done / ❌ Dropped |
| **Priority** | P0 / P1 / P2 / P3 |
| **Owner** | Claude / Pragun |
| **ETA** | <h> |
| **Actual** | — |
| **Created / Updated / Done** | YYYY-MM-DD / YYYY-MM-DD / — |
| **Branch** | `aos-<nnn>-<slug>` |
| **Decisions** | D-x |
| **Blocked by / Blocks** | AOS-<x> / AOS-<y> |

## Description
<What needs to change.>

## Why
<The problem it solves; link to the BRD, a decision, or a bug.>

## Scope
- **In:** <…>
- **Out:** <…>

## Acceptance criteria
- [ ] <Testable condition>

## Implementation plan
1. <step>

## Verification
- <command or manual check, and its expected result>

## Risks / notes
- <…>

## Activity log
- YYYY-MM-DD — <who>: <what happened, result, links to commits>

## Definition of Done (docs/ENGINEERING.md §4)
- [ ] Each acceptance criterion covered by a test or a documented manual check
- [ ] Tests/build green (integration tests if the DB is touched)
- [ ] Layer rules respected; no silent fallbacks; no secrets/PII in logs
- [ ] Decisions recorded · ticket, story, epic and board updated · PROGRESS.md updated
- [ ] Commit message starts with `AOS-<nnn>:`

## Resolution
<Filled when closed: what was done, files changed, how it was verified, Actual vs ETA.>
