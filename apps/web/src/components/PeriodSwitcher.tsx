"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { describe, isCurrent, periodQuery, shift, type Period, type PeriodKind } from "@/lib/period";

const KINDS: { kind: PeriodKind; label: string }[] = [
  { kind: "today", label: "Day" },
  { kind: "week", label: "Week" },
  { kind: "month", label: "Month" },
];

/**
 * ◀ label ▶ + Day/Week/Month (docs/design/console.md §3). Every move is a new URL (history push), so
 * Back returns to the previous period and a refresh keeps it. ▶ is disabled on the current period.
 */
export default function PeriodSwitcher({ period, today }: { period: Period; today: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const search = useSearchParams();
  const go = (p: Period) => router.push(`${pathname}${withPeriod(search, p, today)}`, { scroll: false });
  const atCurrent = isCurrent(period, today);
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div className="flex items-center gap-1">
        <button type="button" aria-label="Previous period" onClick={() => go(shift(period, -1, today))} className="h-11 w-11 rounded-lg text-lg text-ink-muted hover:bg-surface">
          ◀
        </button>
        <h2 className="min-w-36 text-center text-lg font-semibold" aria-live="polite">{describe(period, today)}</h2>
        <button
          type="button"
          aria-label="Next period"
          disabled={atCurrent}
          onClick={() => go(shift(period, 1, today))}
          className="h-11 w-11 rounded-lg text-lg text-ink-muted hover:bg-surface disabled:opacity-30"
        >
          ▶
        </button>
      </div>
      <div role="group" aria-label="Period length" className="flex rounded-lg bg-line p-0.5">
        {KINDS.map(({ kind, label }) => (
          <button
            key={kind}
            type="button"
            aria-pressed={period.kind === kind}
            onClick={() => go({ kind, anchor: today })}
            className={`rounded-md px-3 py-1.5 text-sm ${period.kind === kind ? "bg-surface font-semibold text-ink shadow-xs" : "text-ink-muted"}`}
          >
            {label}
          </button>
        ))}
      </div>
    </div>
  );
}

/** The current query with the period replaced; other parameters (e.g. `view`) are kept. */
export function withPeriod(search: URLSearchParams, period: Period, today: string): string {
  const q = new URLSearchParams(search);
  q.delete("p");
  q.delete("d");
  new URLSearchParams(periodQuery(period, today)).forEach((v, k) => q.set(k, v));
  const s = q.toString();
  return s ? `?${s}` : "";
}
