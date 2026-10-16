/**
 * The console's period, kept in the URL (D-47, docs/design/console.md §3): `?p=month|week|today&d=YYYY-MM-DD`.
 * Pure date arithmetic on calendar days (no timezones): `today` is supplied by the caller in the user's zone.
 */

export type PeriodKind = "today" | "week" | "month";
export interface Period {
  kind: PeriodKind;
  /** Any day inside the period. */
  anchor: string;
}
export interface Range {
  from: string;
  to: string;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const KINDS: PeriodKind[] = ["today", "week", "month"];

const toDate = (s: string) => new Date(`${s}T00:00:00Z`);
const fmt = (d: Date) => d.toISOString().slice(0, 10);
const addDays = (s: string, n: number) => fmt(new Date(toDate(s).getTime() + n * 86_400_000));
function isValidDate(s: string): boolean {
  if (!DATE.test(s)) return false;
  const d = toDate(s);
  return !Number.isNaN(d.getTime()) && fmt(d) === s; // rejects 2026-13-01 and 2026-02-30
}

/** Reads the period from the URL; anything invalid falls back to the current month with a message saying so. */
export function parsePeriod(params: URLSearchParams, today: string): { period: Period; notice: string | null } {
  const p = params.get("p");
  const d = params.get("d");
  const notices: string[] = [];
  let kind: PeriodKind = "month";
  if (p !== null) {
    if ((KINDS as string[]).includes(p)) kind = p as PeriodKind;
    else notices.push(`'${p}' isn't a period`);
  }
  let anchor = today;
  if (d !== null) {
    if (isValidDate(d) && d <= today) anchor = d;
    else notices.push(isValidDate(d) ? `${d} is in the future` : `'${d}' isn't a date`);
  }
  return { period: { kind, anchor }, notice: notices.length ? `Showing ${describe({ kind, anchor }, today)}: ${notices.join(", ")}.` : null };
}

/** The URL query for a period ("" for the default: this month). */
export function periodQuery(period: Period, today: string): string {
  if (period.kind === "month" && sameRange(period, { kind: "month", anchor: today })) return "";
  const q = new URLSearchParams({ p: period.kind, d: period.anchor });
  return `?${q}`;
}

/** First and last day. Weeks start on Monday (D-46). Ranges never extend past today. */
export function rangeOf(period: Period, today: string): Range {
  const a = toDate(period.anchor);
  let from: string;
  let to: string;
  if (period.kind === "today") {
    from = to = period.anchor;
  } else if (period.kind === "week") {
    const dow = (a.getUTCDay() + 6) % 7; // Monday = 0
    from = addDays(period.anchor, -dow);
    to = addDays(from, 6);
  } else {
    from = `${period.anchor.slice(0, 7)}-01`;
    const next = new Date(Date.UTC(a.getUTCFullYear(), a.getUTCMonth() + 1, 1));
    to = fmt(new Date(next.getTime() - 86_400_000));
  }
  return { from, to: to > today ? today : to };
}

function sameRange(a: Period, b: Period): boolean {
  const far = "9999-12-31";
  const ra = rangeOf(a, far);
  const rb = rangeOf(b, far);
  return a.kind === b.kind && ra.from === rb.from;
}

export const isCurrent = (period: Period, today: string) => sameRange(period, { kind: period.kind, anchor: today });

/** One period earlier or later; never past the current one. */
export function shift(period: Period, by: -1 | 1, today: string): Period {
  if (by === 1 && isCurrent(period, today)) return period;
  if (period.kind === "today") return { kind: "today", anchor: addDays(period.anchor, by) };
  if (period.kind === "week") return { kind: "week", anchor: addDays(rangeOf(period, "9999-12-31").from, 7 * by) };
  const a = toDate(period.anchor);
  const first = new Date(Date.UTC(a.getUTCFullYear(), a.getUTCMonth() + by, 1));
  const anchor = fmt(first);
  return { kind: "month", anchor: anchor > today ? today : anchor };
}

const MONTH = new Intl.DateTimeFormat("en-IN", { month: "long", year: "numeric", timeZone: "UTC" });
const DAY = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "UTC" });

/** "October 2026", "This week", "13–19 Oct", "Today", "Yesterday", "14 Oct". */
export function describe(period: Period, today: string): string {
  if (period.kind === "month") return MONTH.format(toDate(period.anchor));
  if (period.kind === "today") {
    if (period.anchor === today) return "Today";
    if (period.anchor === addDays(today, -1)) return "Yesterday";
    return DAY.format(toDate(period.anchor));
  }
  if (isCurrent(period, today)) return "This week";
  const r = rangeOf(period, "9999-12-31");
  const [f, t] = [toDate(r.from), toDate(r.to)];
  return f.getUTCMonth() === t.getUTCMonth()
    ? `${f.getUTCDate()}–${DAY.format(t)}`
    : `${DAY.format(f)} – ${DAY.format(t)}`;
}

/** Today's date (YYYY-MM-DD) in a timezone. */
export function todayIn(zone: string, now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}
