/** Analytics API types (AOS-301). All money is an exact decimal string (D-30). */
export interface Money { spent: string; received: string; net: string }
export interface SummaryData {
  period: { from: string; to: string };
  totals: Money;
  spendCount: number;
  previousPeriod: { from: string; to: string };
  previous: Money;
  spentChangePct: string | null;
}
export interface SeriesPoint { start: string; spent: string; received: string }
export interface SeriesData { period: { from: string; to: string }; bucket: string; points: SeriesPoint[] }
export interface CategoryRow { category: string; spent: string; share: number; count: number; previousSpent: string }
export interface MerchantRow { merchant: string; spent: string; count: number; category: string }

export const analyticsPath = (what: "summary" | "series" | "categories" | "merchants", from: string, to: string, extra: Record<string, string> = {}) =>
  `/api/v1/analytics/${what}?${new URLSearchParams({ from, to, ...extra })}`;

/** "food" → "Food" for display. */
export const categoryLabel = (c: string) => (c === "emi" ? "EMI" : c.charAt(0).toUpperCase() + c.slice(1));
