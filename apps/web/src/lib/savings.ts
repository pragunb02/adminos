/** Savings rate (AOS-908): API types and helpers. Money and rates arrive as exact decimal strings (D-30). */

export interface SavingsMonth { month: string; income: string; spent: string; saved: string; rate: string | null; partial: boolean }
export interface Horizon { years: number; contributed: string; futureValue: string; inTodaysMoney: string }
export interface Projection { month: string; monthlyAmount: string; annualReturnPct: string; inflationPct: string; horizons: Horizon[]; formula: string }
export interface SavingsData {
  months: SavingsMonth[];
  headline: SavingsMonth | null;
  previous: SavingsMonth | null;
  tooLittleData: boolean;
  projection: Projection | null;
  defaults: { annualReturnPct: string; inflationPct: string };
}
export interface AssumptionInput { annualReturnPct: string; inflationPct: string }

export const savingsPath = (a?: AssumptionInput) => {
  const q = new URLSearchParams({ months: "12" });
  if (a) { q.set("annualReturnPct", a.annualReturnPct); q.set("inflationPct", a.inflationPct); }
  return `/api/v1/coach/savings?${q}`;
};

/** "0.3050" → "31%" (whole percent; negative stays negative with a real minus sign). */
export function ratePct(rate: string): string {
  const n = Math.round(Number(rate) * 100);
  return `${n < 0 ? "−" : ""}${Math.abs(n)}%`;
}

const MONTH = new Intl.DateTimeFormat("en-IN", { month: "long", year: "numeric", timeZone: "UTC" });
const SHORT = new Intl.DateTimeFormat("en-IN", { month: "short", timeZone: "UTC" });
export const monthName = (m: string) => MONTH.format(new Date(`${m}-01T00:00:00Z`));
export const monthShort = (m: string) => SHORT.format(new Date(`${m}-01T00:00:00Z`));

/** "▲ 5 points better than September" (arrow + words; colour is never the only signal, D-49). */
export function trendText(now: SavingsMonth, before: SavingsMonth | null): string | null {
  if (!before || now.rate === null || before.rate === null) return null;
  const diff = Math.round(Number(now.rate) * 100) - Math.round(Number(before.rate) * 100);
  if (diff === 0) return `the same as ${monthName(before.month).split(" ")[0]}`;
  return `${diff > 0 ? "▲" : "▼"} ${Math.abs(diff)} points ${diff > 0 ? "better" : "worse"} than ${monthName(before.month).split(" ")[0]}`;
}

/** What the API accepts for a percent: up to 2 digits and 2 decimals, in its range. */
export const validPercent = (s: string, max: number) => /^\d{1,2}(\.\d{1,2})?$/.test(s.trim()) && Number(s) <= max;
