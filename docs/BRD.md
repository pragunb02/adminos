# AdminOS — Business Requirements Document

_Owner: Pragun Bhutani · Last updated: 2026-10-16 (v2, revised with research)_

## 1. Overall goal

A **personal money console** that automatically collects every transaction from every
source and shows, clearly and systematically, **where my money goes each day, week and month**.

It is built **for me first**, and designed so it can **later open to the general public**
without a rewrite.

**North star:** _I open the console and within 10 seconds I know what I spent today, this
month, and on what — without reading a single SMS, email, WhatsApp message, or statement,
and without typing anything in._

## 2. Problems we solve

| # | Problem | Evidence | Our answer (feature) |
|---|---|---|---|
| P1 | **UPI makes spending invisible.** Many small payments, nobody remembers them | IIIT Delhi survey: ~74% spend more since UPI; 59.8% overshot their budget | Automatic capture + daily view |
| P2 | **Forgotten subscriptions and AutoPay mandates** keep charging after use stops | Moneycontrol, LocalCircles (35% hit payment dark patterns often) | Recurring/mandate tracker |
| P3 | **Wrong totals.** Self-transfers counted as income, refunds counted as new spend, duplicates across sources | CRED Money user reviews | Accuracy engine (§5.1) |
| P4 | **Scattered data.** Several banks and cards; cash not tracked at all | Common complaint | Multi-source ingestion + cash quick-add |
| P5 | **Missed dues.** Credit card bills, EMIs, insurance/FD renewals | Statement emails buried | Dues & renewals calendar + reminders |
| P6 | **Tracker fatigue.** Manual entry is tiring and wrong categories kill trust, so people quit within weeks | Budget-app UX studies | Automatic first; corrections remembered |
| P7 | **Email overload.** Bills, tax docs (Form 16, AIS), and warranties are buried under OTPs and promos | — | Gmail import + documents vault (later) |
| P8 | **WhatsApp loss.** Landlord/society bills, payment requests, PDFs get lost in chats | — | WhatsApp forward-to-bot + export import |

## 3. Users

| Phase | User | Notes |
|---|---|---|
| Now | Me (single user, India, INR, **iPhone**) | Self-hosted on my laptop |
| Later | General public (India-first, iPhone and Android) | Multi-user, hosted, onboarding, privacy compliance |

## 4. Inputs (what the user gives)

Every source posts to **one common intake endpoint** (a normalized "ingest event").
Adding a source never touches the core.

| Priority | Source | Personal (now) | Public (later) |
|---|---|---|---|
| P0 | **Bank / card statement** | Upload PDF/CSV/Excel (SBI, HDFC, ICICI, Axis, Kotak, Yes, IndusInd, PNB, BOB) | Same |
| P0 | **iPhone SMS** | iOS Shortcuts automation ("message contains *debited/credited/INR/due*" → POST to API, no confirmation). Optional Mac `chat.db` reader to backfill history | Installable Shortcut template + onboarding |
| P0 | **Cash quick-add** | Fastest possible form (amount + category, two taps) | Same |
| P1 | **Gmail** | Own Google API project, `gmail.readonly`, unverified Production app (exempt under 100 users), incremental sync | **Forwarding address** (Gmail filter → our inbox), avoiding Google's yearly security assessment (CASA); OAuth later |
| P2 | **WhatsApp** | Forward bank/bill messages to my own bot number (WhatsApp Cloud API) + import exported chat `.txt` | Same + Android notification capture |
| P2 | **Android SMS** | — | App with on-device parsing, under Play's "SMS-based money management" exception |
| Later | **Account Aggregator (RBI)** | Not possible (only regulated companies can receive data) | Via a regulated partner |

Parsing aid: Indian SMS sender IDs look like `XY-HDFCBK-T`. Match the 6-character header;
the suffix `-T`/`-S` means keep and `-P` means promotional (drop).

## 5. Outputs (what the user gets)

### 5.1 Accuracy engine (P0, applies to everything)
- **Dedup across sources.** The same payment from SMS, email and statement is counted once.
- **Self-transfer detection.** Money moved between my own accounts is neither spend nor income.
- **Refund/reversal linking.** A refund cancels the original payment instead of being counted as income.
- **Merchant normalization.** UPI VPA, card descriptor and brand become one merchant.
- **Categories I can correct**, and the correction is remembered for that merchant from then on.
- **Debit vs credit** always correct.

### 5.2 Console (P0 — core value)
| View | Shows |
|---|---|
| Today / this week | Spent today, day-by-day for the week |
| Monthly | Spent vs received, vs last month, running total |
| Categories | Share and trend per category |
| Top merchants | Where most money goes |
| Trends | Spending over time (daily/weekly/monthly charts) |
| Feed | Every transaction: search, filter, edit, re-categorize |

### 5.3 Money admin (P1)
- **Recurring and AutoPay tracker:** subscriptions, UPI mandates, price increases.
- **Dues & renewals calendar:** credit card due dates, EMIs, bills, insurance, FDs, with reminders.

### 5.4 Smart extras (P2)
- Unusual-charge alerts (large, foreign, odd-hour, duplicate, ₹1 card-testing).
- Weekly briefing written by Claude, plus an action list.
- **Documents vault:** tax docs, statements, warranties found in email/WhatsApp.

## 6. Milestones

| # | Milestone | Outcome |
|---|---|---|
| 1 | **Foundation runs.** DB starts, `docker compose up` works, login works, common intake endpoint | App opens and I'm logged in |
| 2 | **Data in.** Statement upload, iPhone Shortcuts SMS, cash quick-add, accuracy engine | My transactions in one place, correct, no typing |
| 3 | **Console.** Daily, monthly, categories, merchants, trends, feed | I see where my money goes (**core value**) |
| 4 | **More sources.** Gmail, WhatsApp forward-to-bot and export import, Mac SMS backfill | Nothing missed |
| 5 | **Money admin & smart extras.** Recurring/mandates, dues calendar, alerts, briefing, documents vault | The app tells me what to act on |
| 6 | **Public-ready.** Privacy compliance, Gmail forwarding address, Android app, onboarding, hosting | Others can use it safely |

## 7. Non-functional requirements

- **Privacy:**
  - Data stays in my own database; raw SMS is filtered on the device (only financial messages are sent).
  - No third-party analytics.
- **Privacy compliance before public launch** (DPDP Act, most obligations from 13 May 2027):
  - consent records per source
  - data export and full delete
  - encryption at rest (including OAuth tokens)
  - access logs kept for 1 year
  - breach reporting within 72 hours
- **Self-hostable:** one `docker compose up`.
- **Extensible:**
  - Keep the multi-user data model (`user_id` on every row).
  - One intake endpoint; sources are adapters.
- **Correctness over automation:** every transaction can be corrected by hand, and corrections are kept.
- **India-first:** INR, Indian banks, UPI, TRAI SMS headers.

## 8. Out of scope (for now)

- Investments, stocks, mutual funds, net worth.
- Budgets and goals (may come after Milestone 3).
- Splitting expenses with friends (Splitwise-style).
- Paying bills or cancelling subscriptions on my behalf (drafts and reminders only).
- Account Aggregator (needs a regulated entity).
- **WhatsApp Web automation** (against WhatsApp ToS; risks a number ban).
- iOS SMS filter extensions (they can't read or forward messages).

## 9. Research sources (2026-10-16)

- IIIT Delhi UPI survey: <https://www.indiatoday.in/sunday-special/story/upi-payment-making-indians-spend-more-overspend-save-money-transactions-2535363-2024-05-05>
- Account Aggregator FIU rules: <https://sahamati.org.in/faq/>
- iOS Shortcuts automations: <https://support.apple.com/guide/shortcuts/create-a-new-personal-automation-apdfbdbd7123/ios>
- Gmail restricted scopes / CASA: <https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification>
- Play SMS policy: <https://support.google.com/googleplay/android-developer/answer/10208820>
- WhatsApp ToS: <https://www.whatsapp.com/legal/terms-of-service>
- TRAI SMS header regulation: <https://www.trai.gov.in/sites/default/files/2025-02/Regulation_12022025.pdf>
- DPDP Act timeline: <https://www.dlapiperdataprotection.com/index.html?t=law&c=IN>
