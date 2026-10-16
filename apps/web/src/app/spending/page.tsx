"use client";

import Link from "next/link";
import { Suspense } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import PeriodSwitcher from "@/components/PeriodSwitcher";
import Section, { Skeleton } from "@/components/console/Section";
import CategoryBars from "@/components/console/CategoryBars";
import MerchantTable from "@/components/console/MerchantTable";
import MonthlyBars from "@/components/console/MonthlyBars";
import TrendLine from "@/components/console/TrendLine";
import { isCurrent, rangeOf } from "@/lib/period";
import { analyticsPath, type CategoryRow, type MerchantRow, type SeriesData, type SummaryData } from "@/lib/analytics";
import { periodOnly, useConsolePeriod } from "@/lib/consolePeriod";
import { arrow, delta } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";

const VIEWS = [
  { key: "categories", label: "Categories" },
  { key: "merchants", label: "Merchants" },
  { key: "trends", label: "Trends" },
] as const;
type View = (typeof VIEWS)[number]["key"];

/** Spending (AOS-305, M/Spending): where the money went this period, by category or by merchant. */
function SpendingContent() {
  const router = useRouter();
  const pathname = usePathname();
  const { search, today, period, notice, range, compare } = useConsolePeriod();
  const isCurrentPeriod = isCurrent(period, today);
  const raw = search.get("view");
  const view: View = raw === "merchants" || raw === "trends" ? raw : "categories";
  const chart = search.get("chart") === "lines" ? "lines" : "bars";
  // Trends (AOS-306): 12 months and 12 Monday-weeks ending with the period.
  const monthsFrom = (() => { const d = new Date(`${range.to}T00:00:00Z`); return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - 11, 1)).toISOString().slice(0, 10); })();
  const weeksFrom = (() => { const monday = rangeOf({ kind: "week", anchor: range.to }, "9999-12-31").from; return new Date(new Date(`${monday}T00:00:00Z`).getTime() - 77 * 86_400_000).toISOString().slice(0, 10); })();
  const currentMonth = isCurrentPeriod ? `${today.slice(0, 7)}-01` : null;
  const key = [range.from, range.to];

  const summary = useQuery({ queryKey: ["analytics", "summary", ...key], queryFn: () => getData<SummaryData>(analyticsPath("summary", range.from, range.to)) });
  const compared = useQuery({ queryKey: ["analytics", "summary", "compare", compare.from, compare.to], queryFn: () => getData<SummaryData>(analyticsPath("summary", compare.from, compare.to)) });
  const categories = useQuery({ queryKey: ["analytics", "categories", ...key], enabled: view === "categories", queryFn: () => getData<{ categories: CategoryRow[] }>(analyticsPath("categories", range.from, range.to)) });
  const merchants = useQuery({ queryKey: ["analytics", "merchants", "all", ...key], enabled: view === "merchants", queryFn: () => getData<{ merchants: MerchantRow[] }>(analyticsPath("merchants", range.from, range.to, { limit: "50" })) });
  const months = useQuery({ queryKey: ["analytics", "series", "month", monthsFrom, range.to], enabled: view === "trends", queryFn: () => getData<SeriesData>(analyticsPath("series", monthsFrom, range.to, { bucket: "month" })) });
  const weeks = useQuery({ queryKey: ["analytics", "series", "week", weeksFrom, range.to], enabled: view === "trends", queryFn: () => getData<SeriesData>(analyticsPath("series", weeksFrom, range.to, { bucket: "week" })) });
  const setChart = (c: "bars" | "lines") => {
    const q = new URLSearchParams(search);
    if (c === "bars") q.delete("chart"); else q.set("chart", c);
    router.push(`${pathname}?${q}`, { scroll: false });
  };

  const switchTo = (v: View) => {
    const q = new URLSearchParams(search);
    if (v === "categories") q.delete("view"); else q.set("view", v);
    if (v !== "trends") q.delete("chart");
    const s = q.toString();
    router.push(`${pathname}${s ? `?${s}` : ""}`, { scroll: false });
  };
  const keep = periodOnly(search);

  return (
    <div className="space-y-4">
      <PeriodSwitcher period={period} today={today} />
      {notice && <p role="status" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">{notice}</p>}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="tabular text-sm text-ink-muted" aria-live="polite">
          {summary.data && compared.data ? (
            <>
              Total <span className="font-semibold text-ink">{formatInr(summary.data.totals.spent)}</span>
              {" · "}
              {(() => {
                const d = delta(summary.data.totals.spent, compared.data.totals.spent);
                return d.direction === "same" ? `same as ${compare.label}` : <><span aria-hidden>{arrow(d.direction)} </span>{d.text} than {compare.label}</>;
              })()}
            </>
          ) : summary.error ? errorMessage(summary.error) : " "}
        </p>
        <div role="group" aria-label="Show by" className="flex rounded-lg bg-line p-0.5">
          {VIEWS.map((v) => (
            <button key={v.key} type="button" aria-pressed={view === v.key} onClick={() => switchTo(v.key)}
              className={`rounded-md px-3 py-1.5 text-sm ${view === v.key ? "bg-surface font-semibold text-ink shadow-xs" : "text-ink-muted"}`}>
              {v.label}
            </button>
          ))}
        </div>
      </div>

      {view === "trends" ? (
        <div className="space-y-4">
          <Section
            title="Last 12 months"
            action={
              <div role="group" aria-label="Chart type" className="flex rounded-md bg-line p-0.5 text-xs">
                {(["bars", "lines"] as const).map((c) => (
                  <button key={c} type="button" aria-pressed={chart === c} onClick={() => setChart(c)} className={`min-h-6 rounded px-2 py-1 ${chart === c ? "bg-surface font-semibold" : "text-ink-muted"}`}>
                    {c === "bars" ? "Bars" : "Lines"}
                  </button>
                ))}
              </div>
            }
            error={months.error ? errorMessage(months.error) : null}
            onRetry={() => months.refetch()}
          >
            {months.data ? <MonthlyBars points={months.data.points} kind={chart} currentMonth={currentMonth} /> : <Skeleton lines={5} />}
          </Section>
          <Section title="Last 12 weeks" error={weeks.error ? errorMessage(weeks.error) : null} onRetry={() => weeks.refetch()}>
            {weeks.data ? <TrendLine points={weeks.data.points} bucket="week" title="Spending by week" /> : <Skeleton lines={4} />}
          </Section>
        </div>
      ) : view === "categories" ? (
        <Section title="By category" error={categories.error ? errorMessage(categories.error) : null} onRetry={() => categories.refetch()}>
          {categories.data ? (
            categories.data.categories.length ? (
              <CategoryBars rows={categories.data.categories} hrefFor={(c) => `/spending/category/${c}${keep}`} />
            ) : <p className="text-sm text-ink-muted">Nothing spent in this period.</p>
          ) : <Skeleton lines={5} />}
        </Section>
      ) : (
        <Section title="By merchant" error={merchants.error ? errorMessage(merchants.error) : null} onRetry={() => merchants.refetch()}>
          {merchants.data ? (
            merchants.data.merchants.length ? <MerchantTable rows={merchants.data.merchants} /> : <p className="text-sm text-ink-muted">No merchants in this period.</p>
          ) : <Skeleton lines={5} />}
        </Section>
      )}
      <p className="text-xs text-ink-muted">
        Spending is purchases minus refunds; transfers between your own accounts don&apos;t count. <Link href={`/transactions${keep}`} className="underline">See every transaction</Link>
      </p>
    </div>
  );
}

export default function SpendingPage() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={4} />}>
        <SpendingContent />
      </Suspense>
    </AppShell>
  );
}
