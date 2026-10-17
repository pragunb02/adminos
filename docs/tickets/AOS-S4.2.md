# AOS-S4.2 — WhatsApp capture

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E4](AOS-E4.md) |
| **Status** | 🟦 In progress |
| **Priority** | P1 |
| **Owner** | Claude |
| **ETA (rollup)** | 9h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-17 |
| **Decisions** | D-17, D-66 |

## User story
**As** Pragun (owner, India, iPhone), **I want** to forward or import WhatsApp money messages, **so that** bills in chats aren't lost.

## Acceptance criteria
- [ ] Forward-to-bot works
- [x] Export import works (AOS-403)

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| AOS-402 | WhatsApp forward-to-bot (Cloud API) | Claude + Pragun | ⬜ | 6h |  |
| [AOS-403](AOS-403.md) | WhatsApp chat export import (`.txt` / `.zip`), bank senders only (D-66) | Claude | ✅ | 3h | 2h |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Forward a bill

## Activity log
- 2026-10-17 — Claude: AOS-403 done (WhatsApp export import). Left: AOS-402 (needs Pragun's WhatsApp Cloud API setup).
- 2026-10-16 — Claude: story created (AOS-004).
