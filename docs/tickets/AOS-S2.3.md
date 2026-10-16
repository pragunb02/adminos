# AOS-S2.3 — iPhone SMS auto-capture

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E2](AOS-E2.md) |
| **Status** | 🟦 In progress (code done; AOS-211 real-device setup) |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 5h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-16 |

## User story
**As** Pragun (owner, India, iPhone), **I want** bank SMS on my iPhone captured automatically, **so that** I never type UPI spends.

## Acceptance criteria
- [ ] The Shortcut posts the SMS; a transaction appears within a minute
- [ ] Promotional SMS (-P) ignored

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-203](AOS-203.md) | iPhone Shortcuts SMS adapter + SMS parser + setup guide | Claude | ⬜ | 5h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Make a UPI payment and see it appear

## Activity log
- 2026-10-16 — Claude: AOS-203 done. The transport is the Mac Messages reader (D-35), not a Shortcut, for now.
- 2026-10-16 — Claude: story created (AOS-004).
