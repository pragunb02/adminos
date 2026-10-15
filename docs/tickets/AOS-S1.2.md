# AOS-S1.2 — I can log in with Google

| Field | Value |
|---|---|
| **Type** | Story |
| **Parent** | [AOS-E1](AOS-E1.md) |
| **Status** | 🟦 In progress |
| **Priority** | P0 |
| **Owner** | Claude |
| **ETA (rollup)** | 7.25h |
| **Actual** | — |
| **Created / Updated** | 2026-10-16 / 2026-10-16 |
| **Decisions** | D-14, D-18 |

## User story
**As** Pragun (owner, India, iPhone), **I want** to sign in with my Google account, **so that** my data is private to me and ready for public users later.

## Acceptance criteria
- [ ] Given I click 'Sign in with Google', when I approve, then I land on the dashboard (or onboarding)
- [ ] Given I'm logged in, when I refresh after 1h, then I'm still logged in
- [ ] Given I click logout, then my session ends

## Tasks
| ID | Task | Owner | Status | ETA | Actual |
|---|---|---|---|---|---|
| [AOS-106](AOS-106.md) | Auth reads the JWT from the `access_token` cookie too | Claude | ✅ | 1h | 0.5h |
| [AOS-107](AOS-107.md) | JSON contract web ↔ API (snake_case vs camelCase) | Claude | ⬜ | 2h |  |
| [AOS-108](AOS-108.md) | Web config: Google client ID, browser API URL, CORS | Claude | ⬜ | 1h |  |
| [AOS-109](AOS-109.md) | Session refresh via cookie + logout button | Claude | ⬜ | 2h |  |
| [AOS-111](AOS-111.md) | Create Google OAuth client | Pragun | ⬜ | 0.25h |  |
| [AOS-112](AOS-112.md) | End-to-end login on laptop | Claude + Pragun | ⬜ | 1h |  |

_Task tickets are created as files when this story is picked up._

## Demo / verification
Pragun logs in on http://localhost:3000

## Activity log
- 2026-10-16 — Claude: story created (AOS-004).
