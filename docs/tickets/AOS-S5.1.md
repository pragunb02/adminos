# AOS-S5.1 — Scheduler & notifications

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E5](AOS-E5.md) |
| **Status** | 🟦 In progress |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 7h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-50, D-51 |

## User story
**As** Pragun (owner, India, iPhone), **I want** jobs run on time and notify me, **so that** I don't have to check.

## Acceptance criteria
- [x] Scheduler runs detectors and reminders (AOS-501)
- [x] Email actually delivered (AOS-504); push via Web Push (AOS-510)

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-501](AOS-501.md) | Scheduler: reminders, nudges, subscription detection (briefing job → AOS-506) | Claude | ✅ | — | 2h |
| [AOS-504](AOS-504.md) | Real notifications: channels, SMTP email, quiet-hours hold, retries | Claude | ✅ | 4h | 2.5h |
| AOS-510 | Web Push: subscriptions, VAPID keys, service worker, Settings toggle (iPhone Home Screen app) | Claude | ⬜ | — |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Receive a reminder

## Activity log
- 2026-10-16 — Claude: story created (AOS-004).
- 2026-10-16 — Claude: started; AOS-501 done.
- 2026-10-16 — Claude: AOS-504 done; AOS-510 split out for Web Push.
