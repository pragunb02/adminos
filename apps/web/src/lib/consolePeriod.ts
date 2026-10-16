"use client";

import { useSearchParams } from "next/navigation";
import { browserZone } from "./format";
import { comparisonRange, parsePeriod, rangeOf, todayIn } from "./period";

/** The period every console page reads from its URL (D-47): the same `?p=&d=` on Home, Spending, Activity. */
export function useConsolePeriod() {
  const search = useSearchParams();
  const zone = browserZone();
  const today = todayIn(zone);
  const { period, notice } = parsePeriod(search, today);
  return { search, zone, today, period, notice, range: rangeOf(period, today), compare: comparisonRange(period, today) };
}

/** The query string that keeps the current period when linking to another console page. */
export function periodOnly(search: URLSearchParams): string {
  const q = new URLSearchParams();
  for (const k of ["p", "d"]) {
    const v = search.get(k);
    if (v) q.set(k, v);
  }
  const s = q.toString();
  return s ? `?${s}` : "";
}

/** Every category the API knows (TransactionCategory). */
export const CATEGORIES = ["food", "transport", "shopping", "utilities", "emi", "subscription", "entertainment", "health", "education", "transfer", "other"] as const;
