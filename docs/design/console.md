# AdminOS console — design spec (AOS-302)

Status: **Accepted** (Plan 3 approved; Pragun may still change it before AOS-303 ships) · 2026-10-16 · Decisions D-45, D-46, D-47, D-49.
Each frame below is named so it can be redrawn 1:1 in Figma (page "Console", frames `M/…` for mobile at 390×844 and `D/…` for desktop at 1440×900).

## 1. What the console is for

> **I open it and in 10 seconds I know what I spent today, this month, and on what.** (Epic AOS-E3)

Pragun asked for it to be **minimal, calm, easy to scroll, and good for analysis**.

**Home answers one question.** Deeper questions go to their own screens, where you can scroll and filter.

## 2. Principles (from the research; sources in §12)

1. **One headline per screen.** Show the number first, then what needs attention, then the explanation. [NN/g dashboards]
2. **Use length and position, not area or angle.**
   - Bars for comparisons, lines for change over time.
   - **No pies or donuts, no stacked bars, no gauges, no 3D.** [NN/g chart types]
3. **Compare like with like.** "This month so far" is compared with *the same days* of last month, never with the whole of last month.
4. **Colour is never the only signal.**
   - Refunds show "+" and a "Refund" label.
   - Chart parts have at least 3:1 contrast; text has at least 4.5:1 (WCAG 1.4.1, 1.4.11; enforced by axe in AOS-311).
5. **A colour-blind-safe palette** (Okabe–Ito) for categories; everything else is grey plus one accent.
6. **Undo instead of "Are you sure?".** Re-categorize, confirm or reject a suggestion, then a toast offers **Undo**. Confirmations are only for things that can't be undone. [NN/g confirmation dialogs]
7. **State lives in the URL** (D-47): period, filters and search, so back/forward, refresh and sharing work. [NN/g URL as UI]
8. **Mobile filters apply in one batch,** with an explicit **Apply**. Nothing jumps while you choose. [NN/g applying filters]
9. **Calm by default:**
   - no red/green "stoplight" alerts on every number;
   - one accent colour;
   - plenty of white space;
   - motion only to show where something went (150 ms).
10. **Data quality before insight.** Unconfirmed duplicates, transfers and refunds change the totals, so "N to review" sits right under the headline.

## 3. Navigation

### Mobile (< 640px): bottom tab bar, exactly 5 items (fixes the AOS-311 finding)

| Tab | Route | Purpose |
|---|---|---|
| **Home** | `/` | Today, this week, this month at a glance |
| **Spending** | `/spending` | Categories, merchants, trends |
| **Activity** | `/transactions` | The feed: search, filter, edit |
| **Review** | `/review` | Suggested duplicates/transfers/refunds (badge = count) |
| **More** | `/more` | Import, Bills, Subscriptions, Briefings, Settings, Log out |

- **+ Cash** is a floating button above the tab bar, on Home, Spending and Activity only.

### Desktop (≥ 1024px): left rail
- The same 5 destinations, with **More** expanded into its items, and **+ Cash** at the top of the rail.
- Content is at most 1120px wide: a main column, plus a right column on Home and Month.

### Tablet (640–1023px)
- A rail with icons only, and labels as tooltips.

### Period switcher (Home, Spending, Month)

`◀  October 2026  ▶`

- **◀ / ▶** move one period.
- **▶ is disabled on the current period.**
- Tapping the label opens a picker: Today · This week · This month · Last month · Custom.
- Each move to another period **pushes** a history entry (Back returns to the previous period).
- Typing in search and changing a slider **replace** the current entry.

### URL schema (all optional; the default is the current period in the user's timezone, AOS-120)

```
/                         ?p=month|week|today  &d=2026-10-16
/spending                 ?m=2026-10  &view=categories|merchants|trends  &cat=food
/spending/category/food   ?m=2026-10
/transactions             ?q=swiggy&cat=food&type=spend|income|refund|transfer&from=2026-10-01&to=2026-10-31
/review                   ?kind=duplicate|transfer|refund|reversal
```

- Every value is validated. A bad value falls back to the default **and** shows "Showing this month; '2026-13' isn't a month".

## 4. Frames

Frames use mobile-first ASCII. Each line maps to a component in §7.

### M/Home — `/` (scroll top → bottom)
```
┌──────────────────────────────────────┐
│ AdminOS                       (avatar)│  header 56
│ ◀   October 2026   ▶     [Month ▾]   │  PeriodSwitcher
├──────────────────────────────────────┤
│ Spent so far                          │
│ ₹48,250                               │  Hero (display 40/48, tabular nums)
│ ₹4,200 more than by 16 Sep  ▲ 9.5%    │  Delta: text + arrow, neutral colour
│ Received ₹1,00,000 · Net +₹51,750     │  secondary row
├──────────────────────────────────────┤
│ ⚑ 3 to review — they change totals  › │  ReviewBanner (only if >0)
├──────────────────────────────────────┤
│ Running total                         │  CumulativeLine: this month (solid)
│  ╱‾‾‾‾╱‾‾‾                            │  vs last month (dashed, grey), day 1…31
│ 1        10        20        31       │  "today" marker; text alt below
├──────────────────────────────────────┤
│ This week   Mon–Sun                   │  DailyBars (7 columns, today outlined)
│ ▁ █ ▃ ▁ ▅ · ·    Today ₹350           │
├──────────────────────────────────────┤
│ Where it went                    All ›│  CategoryBars: top 5, sorted
│ Shopping  ████████████   ₹18,400 38%  │  bar + ₹ + % (direct labels, no legend)
│ Food      ███████        ₹11,200 23%  │  ▲/▼ vs same days last month (text)
│ …                                     │
├──────────────────────────────────────┤
│ Top merchants                    All ›│  MerchantList: top 5
│ Amazon  ₹9,999 · 4   Swiggy ₹6,120 ·18│
├──────────────────────────────────────┤
│ Latest                       Activity ›│  last 5 transactions (FeedRow)
├──────────────────────────────────────┤
│ Coming up: HDFC card ₹24,500 · 2 days │  BillsStrip (if any due ≤7d)
└──────────────────────────────────────┘
          [ + Cash ]                       floating
 Home  Spending  Activity  Review(3)  More   TabBar
```

- `p=week` changes the hero to "This week", the line to 7 days, and the bars to days.
- `p=today` shows today's total, and the list shows every transaction today.

### M/Spending — `/spending?m=2026-10&view=categories`
```
◀ October 2026 ▶                [Categories | Merchants | Trends]  SegmentedControl (URL: view)
Total ₹48,250 · vs Sep (same days) ▲ 9.5%
Shopping   ████████████  ₹18,400  38%  ▲ ₹2,100
Food       ███████       ₹11,200  23%  ▼ ₹800
Transport  ███           ₹4,300    9%
…  (all categories, sorted; a refund-only category shows −₹300 "Refunds only", D-46)
```
- Tapping a row → `/spending/category/food`:
  - the category's 6-month trend (line);
  - its top merchants;
  - its transactions (FeedRow), linking to the filtered Activity.
- **Merchants:** the same layout, by merchant, with count and average per visit.
- **Trends:** monthly spent and received over 12 months (paired bars, or a line, user toggle), plus a weekly line for the last 12 weeks. Each chart has a "View as table" toggle.

### M/Activity — `/transactions`
```
[ 🔍 Search merchant or note        ]  (replaceState while typing, 300 ms)
[All ▾] [Category ▾] [Date ▾]   Filters (sheet with Apply)   chips show active filters ×
Today
  Swiggy            Food · UPI ··1234          −₹349
  Amazon refund     Shopping · Refund          +₹300   (label + sign, not colour only)
Yesterday
  …                                         Load more / infinite scroll (keeps position on Back)
```
- Tapping a row opens a **TransactionSheet**:
  - category picker (remember for this payee: on, D-41);
  - note;
  - source;
  - links (merged with SMS, refund of …).
- A change shows the toast "Food → Health. Undo".

### M/Review — `/review`
```
3 to review  ·  [All | Duplicates | Transfers | Refunds]
┌ Possible duplicate ─────────────────┐
│ Swiggy ₹349 · SMS 9:12   ↔  Statement 16 Oct │
│ Same reference 1234…   Score 9                │
│ [Same payment]  [Different]                   │
└───────────────────────────────────────────────┘
```
- Either button removes the card, with an **Undo** toast.
- When the list is empty: "All clear — your totals are final."

### M/More — `/more`
- A plain list: Import statement · Bills · Subscriptions · Briefings · Settings · Log out.

### Desktop frames
- **D/Home** is the same content in two columns:
  - left: hero, running total, week;
  - right: review banner, categories, merchants, bills.
- **D/Spending** and **D/Activity:** one column, max 880px; the filters sit in a bar instead of a sheet.

## 5. States (every frame)

| State | Look |
|---|---|
| Loading | Skeletons in the final layout (no layout shift); the hero shows `₹—` |
| Empty (new user) | "No transactions yet" + three actions: Import a statement · Connect SMS · Add cash |
| Empty (period) | "Nothing spent in this period" + ◀ to the previous one |
| Error | Inline card: the message + **Try again**; the rest of the screen still shows what loaded |
| Offline | A banner, "Offline: showing what you saw last"; cached data stays (TanStack Query) |
| Partial data | Review banner: "3 to review — they change totals" |

## 6. Numbers and text

- **Money:**
  - `Intl.NumberFormat('en-IN', {style:'currency', currency:'INR', maximumFractionDigits: 0})` in summaries, which gives ₹12,34,567;
  - paise shown in transaction detail (2 decimals);
  - amounts come from the API as exact decimal strings (D-30, AOS-611) and are never added up in floating point in the browser.
- **Compact** on chart axes and small tiles only: our own formatter, ₹1.2K / ₹12.3L / ₹1.2Cr (en-IN compact output is not reliable across browsers).
- **Signs:**
  - spend shows `−₹349`;
  - income, refunds and reversals show `+₹300` plus a label;
  - deltas show `▲ ₹2,100` / `▼ ₹800` plus text ("more/less than by 16 Sep").
- **Tabular figures** (`font-variant-numeric: tabular-nums`) for every number, so columns line up.
- **Dates:**
  - relative within a week (Today, Yesterday, Mon);
  - otherwise "16 Oct";
  - the year only if it isn't this year;
  - always in the user's timezone.

## 7. Components

| Component | Notes | Chart (Recharts) |
|---|---|---|
| `PeriodSwitcher` | ◀ label ▶ + picker; URL-driven | — |
| `Hero` | value, delta sentence, secondary row | — |
| `ReviewBanner` | count + why it matters → /review | — |
| `CumulativeLine` | this vs last period, today marker, text alternative | `LineChart`, 2 series, no dots |
| `DailyBars` | 7 or 31 columns, today outlined, zero days visible | `BarChart` |
| `CategoryBars` | sorted horizontal bars with direct labels | plain HTML/CSS bars (accessible, no chart lib needed) |
| `MerchantList` | rows: name, count, total | — |
| `TrendChart` | 12 months spent/received; table toggle | `BarChart` (paired) / `LineChart` |
| `FeedRow` | merchant, category · method · ··last4, signed amount, label | — |
| `TransactionSheet` | edit category (remember), note, links | — |
| `SuggestionCard` | both sides, reason, score, two actions | — |
| `UndoToast` | 5s, focusable, Undo button | — |
| `FilterSheet` | batch filters + Apply | — |
| `TabBar` / `Rail` | 5 destinations, badge on Review | — |

- Every chart:
  - has `role="img"` and a one-sentence `aria-label` (e.g. "Spent ₹48,250 by 16 Oct, ₹4,200 more than by 16 Sep");
  - has a "View as table" toggle;
  - uses no legend (labels sit directly on the chart).

## 8. Tokens

**Colour** (light theme; dark theme later; all text ≥ 4.5:1, chart parts ≥ 3:1)

| Token | Value | Use |
|---|---|---|
| `ink` | gray-900 `#111827` | primary text, hero |
| `ink-muted` | gray-600 `#4b5563` | secondary text (gray-400/500 are too light on tinted backgrounds, AOS-311) |
| `line` | gray-200 `#e5e7eb` | dividers, card borders |
| `surface` | white / gray-50 `#f9fafb` | cards / page |
| `accent` | blue-700 `#1d4ed8` | links, active tab, primary action, this-period series |
| `compare` | gray-400 `#9ca3af` dashed | last-period series (graphic, with a label, so 3:1 is enough) |
| `positive` / `negative` | green-700 / red-700 | only beside a sign and a label |
| Categories | Okabe–Ito: `#0072B2` `#E69F00` `#009E73` `#CC79A7` `#56B4E9` `#D55E00` `#F0E442`(bars only, not text) `#000000` | category bars/dots, fixed per category |

**Type:**
- system UI font;
- sizes 12 / 14 / 16 / 20 / 28 / 40 (hero);
- weight 400/500/600;
- line height 1.4; numbers use tabular-nums.

**Space:**
- a 4px grid;
- cards have 16px padding and 12px gaps;
- the screen gutter is 16px (mobile) / 24px (desktop).

**Shape:**
- 12px radius on cards, 8px on controls;
- no shadows except on sheets and toasts.

**Motion:**
- 150 ms ease-out for sheets and toasts;
- `prefers-reduced-motion` turns it off.

**Touch:**
- targets at least 44×44 px;
- the tab bar is 56px high, plus the safe area.

## 9. Interactions

- **Pull to refresh** on Home and Activity (mobile).
- **Back:**
  - restores the scroll position and the open period (the TanStack cache plus URL state);
  - from the TransactionSheet, it closes the sheet (the sheet is a URL state `?txn=id`).
- **Keyboard (desktop):**
  - `/` focuses search;
  - `←/→` move the period on Home and Spending;
  - `c` opens + Cash;
  - `Esc` closes sheets.
- **Undo:** confirm, reject and re-categorize happen at once (optimistic) and can be undone for 5 s.

## 10. What changes from today's app

- **Navigation:** 8 items → 5 (Import, Bills, Subscriptions, Briefings and Settings move under **More**). This fixes the unreachable Log out on phones.
- **Home:** the action-item list → console Home. Bills and anomalies become a strip and the Review badge.
- **Onboarding copy:** Android → iPhone/Mac SMS reader + statements + cash (D-19).
- **Dead UI removed:** the subscriptions "cancellation draft" modal.

## 11. Delivery (each ticket ships with unit + Playwright tests, D-47)

| Ticket | Scope |
|---|---|
| AOS-302 | This spec; tokens + formatters (`money`, `compactMoney`, `delta`, `relativeDate`); TabBar/Rail; PeriodSwitcher with URL state |
| AOS-303 | Home: hero, review banner, week bars, latest, bills strip |
| AOS-304 | Month: running-total line, daily bars, same-days comparison |
| AOS-305 | Spending: categories + merchants + category detail |
| AOS-306 | Trends: 12 months + 12 weeks + table toggle |
| AOS-307 | Activity: feed with URL filters, FilterSheet, TransactionSheet, undo (needs AOS-611 exact amounts + nature) |
| AOS-309 | Review: SuggestionCard, kinds, undo |

## 12. Sources (fetched 2026-10-16)

- NN/g, dashboards and preattentive processing — https://www.nngroup.com/articles/dashboards-preattentive/
- NN/g, choosing chart types — https://www.nngroup.com/articles/choosing-chart-types/
- NN/g, confirmation dialogs — https://www.nngroup.com/articles/confirmation-dialog/
- NN/g, applying filters — https://www.nngroup.com/articles/applying-filters/
- NN/g, URL as UI — https://www.nngroup.com/articles/url-as-ui/
- NN/g, mobile navigation patterns — https://www.nngroup.com/articles/mobile-navigation-patterns/
- WCAG 2.2, use of colour — https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html
- WCAG 2.2, non-text contrast — https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html
- Okabe & Ito, colour-universal design — https://jfly.uni-koeln.de/color/
- Tufte, sparklines — https://www.edwardtufte.com/notebook/sparkline-theory-and-practice-edward-tufte/
- Android adaptive navigation — https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation
- MDN, History.replaceState — https://developer.mozilla.org/en-US/docs/Web/API/History/replaceState
- MDN, Intl.NumberFormat — https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/NumberFormat/NumberFormat
- Indian numbering system — https://en.wikipedia.org/wiki/Indian_numbering_system
- Fold (Indian money app, "calm" design) — https://fold.money/ · Copilot Money — https://www.copilot.money/

**Not verified:** Monarch, Lunch Money, Jupiter, Fi, CRED, Revolut, Baymard and the Figma community templates could not be fetched, so nothing here relies on them.
