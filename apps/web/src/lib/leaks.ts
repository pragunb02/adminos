/**
 * "Where it leaks" (AOS-902): API types, paths and URL helpers. Money is an exact decimal string (D-30);
 * shares and changes arrive as decimal strings too and are only rounded for display here.
 */
import { parsePeriod, rangeOf, type Range } from "./period";

export interface LateNightNumbers { orders: number; timedOrders: number; share: string | null; spent: string }
export interface WeekdayNumbers { day: Weekday; orders: number; spent: string }
export interface LeakNumbers {
  spent: string;
  orders: number;
  averageOrder: string | null;
  lateNight: LateNightNumbers;
  weekdays: WeekdayNumbers[];
  previousSpent: string;
  changePct: string | null;
}
export interface MerchantLeak { merchant: string; numbers: LeakNumbers }
export interface LensLeak { id: string; label: string; description: string; numbers: LeakNumbers; merchants: MerchantLeak[] }
export interface LeaksData {
  period: Range;
  previousPeriod: Range;
  lateNight: { from: string; to: string };
  total: LeakNumbers;
  lenses: LensLeak[];
}
export interface LensDefinition {
  id: string; label: string; description: string;
  brands: string[]; categories: string[]; subcategories: string[]; keywords: string[];
  added: string[]; removed: string[];
}
export interface LensesData { version: string; lateNight: { from: string; to: string }; lenses: LensDefinition[] }

export const WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;
export type Weekday = (typeof WEEKDAYS)[number];
export const WEEKDAY_LABEL: Record<Weekday, string> = { mon: "Mon", tue: "Tue", wed: "Wed", thu: "Thu", fri: "Fri", sat: "Sat", sun: "Sun" };

export const LENSES_PATH = "/api/v1/coach/lenses";
/** PUT `{merchant, include}` here to add or take out a merchant. */
export const lensOverridePath = (lens: string) => `${LENSES_PATH}/${encodeURIComponent(lens)}/merchants`;
/** DELETE this to go back to the lens's own rules for the merchant. */
export const lensResetPath = (lens: string, merchant: string) => `${lensOverridePath(lens)}?${new URLSearchParams({ merchant })}`;

export const leaksPath = (range: Range) => `/api/v1/coach/leaks?${new URLSearchParams({ from: range.from, to: range.to })}`;

/** What a number is made of, as Activity filters (Activity reads the same names, AOS-902). */
export interface LeakSlice { lens?: string; merchant?: string; late?: boolean; weekday?: Weekday }

export function activityHref(range: Range, slice: LeakSlice = {}): string {
  const q = new URLSearchParams({ lens: slice.lens ?? "all", from: range.from, to: range.to });
  if (slice.merchant) q.set("merchant", slice.merchant);
  if (slice.late) q.set("late", "1");
  if (slice.weekday) q.set("weekday", slice.weekday);
  return `/transactions?${q}`;
}

/** The API path for the transactions behind an Activity lens filter. */
export function leakTransactionsPath(f: { lens: string; merchant: string; late: boolean; weekday: string; from: string; to: string; category: string; nature: string; q: string }): string {
  const q = new URLSearchParams({ lens: f.lens, limit: "1000" });
  if (f.from && f.to) { q.set("from", f.from); q.set("to", f.to); }
  if (f.merchant) q.set("merchant", f.merchant);
  if (f.late) q.set("lateNight", "true");
  if (f.weekday) q.set("weekday", f.weekday);
  if (f.category) q.set("category", f.category);
  if (f.nature === "spend" || f.nature === "refund") q.set("nature", f.nature);
  if (f.q) q.set("q", f.q);
  return `/api/v1/coach/leaks/transactions?${q}`;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
function validDate(s: string): boolean {
  if (!DATE.test(s)) return false;
  const d = new Date(`${s}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s; // rejects 2026-13-01 and 2026-02-30
}

/**
 * The page's range from the URL: a custom `from`/`to` when both are valid, otherwise the console period
 * (`p`/`d`, default this month). A bad custom range falls back with a notice saying why.
 */
export function readLeakRange(search: URLSearchParams, today: string): { range: Range; custom: boolean; notice: string | null } {
  const from = search.get("from");
  const to = search.get("to");
  if (from !== null || to !== null) {
    const problem =
      !from || !to ? "give both a start and an end date"
        : !validDate(from) || !validDate(to) ? "dates look like 2026-10-01"
          : from > to ? "the start is after the end"
            : to > today ? "the end is in the future"
              : daysBetween(from, to) > MAX_DAYS ? `a range can cover at most ${MAX_DAYS} days`
                : null;
    if (!problem) return { range: { from: from!, to: to! }, custom: true, notice: null };
    const { period } = parsePeriod(search, today);
    return { range: rangeOf(period, today), custom: false, notice: `Showing the period instead: ${problem}.` };
  }
  const { period, notice } = parsePeriod(search, today);
  return { range: rangeOf(period, today), custom: false, notice };
}

/** Like the API (3 × 366 days). */
export const MAX_DAYS = 1098;
const daysBetween = (a: string, b: string) => Math.round((Date.parse(b) - Date.parse(a)) / 86_400_000) + 1;

/** "0.6667" → "67%". */
export const percent = (share: string) => `${Math.round(Number(share) * 100)}%`;

/** "140.0" → "▲ 140% more", "-25.5" → "▼ 26% less" (arrow + words: colour is never the only signal). */
export function changeText(changePct: string | null): string | null {
  if (changePct === null) return null;
  const n = Math.round(Number(changePct));
  if (n === 0) return "about the same as before";
  return n > 0 ? `▲ ${n}% more than before` : `▼ ${-n}% less than before`;
}

/** "7 apps and shops; anything categorised food delivery; names with bar, pub, club…" */
export function ruleSummary(d: Pick<LensDefinition, "brands" | "categories" | "subcategories" | "keywords">): string {
  const parts: string[] = [];
  if (d.brands.length) parts.push(`${d.brands.length} ${d.brands.length === 1 ? "app or shop" : "apps and shops"}`);
  const kinds = [...d.categories, ...d.subcategories].map((c) => c.replaceAll("_", " "));
  if (kinds.length) parts.push(`anything categorised ${kinds.join(", ")}`);
  if (d.keywords.length) parts.push(`names with ${d.keywords.slice(0, 3).join(", ")}${d.keywords.length > 3 ? "…" : ""}`);
  return parts.length ? parts.join("; ") : "only the merchants you add";
}
