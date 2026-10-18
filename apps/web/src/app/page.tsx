"use client";

import Link from "next/link";
import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import PeriodSwitcher from "@/components/PeriodSwitcher";
import Section, { Skeleton } from "@/components/console/Section";
import Hero from "@/components/console/Hero";
import ReviewBanner from "@/components/console/ReviewBanner";
import ForecastCard from "@/components/console/ForecastCard";
import DailyBars from "@/components/console/DailyBars";
import CumulativeLine from "@/components/console/CumulativeLine";
import { cumulativeByDay } from "@/lib/cumulative";
import { delta } from "@/lib/format";
import CategoryBars from "@/components/console/CategoryBars";
import MerchantList from "@/components/console/MerchantList";
import LeaksHomeCard from "@/components/leaks/LeaksHomeCard";
import FeedRow from "@/components/console/FeedRow";
import { analyticsPath, type CategoryRow, type MerchantRow, type SeriesData, type SummaryData } from "@/lib/analytics";
import { browserZone } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { comparisonRange, describe as describePeriod, isCurrent, parsePeriod, rangeOf, shift, todayIn, type Period } from "@/lib/period";
import { errorMessage, getData } from "@/lib/query";
import type { Bill, DashboardHome, Transaction } from "@/lib/types";

/** Monday–Sunday around a day (the "This week" bars always show a whole week). */
function weekAround(day: string): { from: string; to: string } {
  return rangeOf({ kind: "week", anchor: day }, "9999-12-31");
}

/**
 * Console Home (AOS-303, docs/design/console.md "M/Home"): one question — what did I spend, and on what.
 * Every section loads on its own; one failing section shows its own retry while the rest still render.
 */
function HomeContent() {
  const zone = browserZone();
  const today = todayIn(zone);
  const { period, notice } = parsePeriod(useSearchParams(), today);
  const range = rangeOf(period, today);
  const compare = comparisonRange(period, today);
  const week = weekAround(range.to);
  const key = [period.kind, range.from, range.to];

  const summary = useQuery({ queryKey: ["analytics", "summary", ...key], queryFn: () => getData<SummaryData>(analyticsPath("summary", range.from, range.to)) });
  const compared = useQuery({ queryKey: ["analytics", "summary", "compare", compare.from, compare.to], queryFn: () => getData<SummaryData>(analyticsPath("summary", compare.from, compare.to)) });
  const isMonth = period.kind === "month";
  const prevMonth = rangeOf(shift({ kind: "month", anchor: range.from }, -1, today), today);
  const series = useQuery({ queryKey: ["analytics", "series", week.from, week.to], enabled: !isMonth, queryFn: () => getData<SeriesData>(analyticsPath("series", week.from, week.to, { bucket: "day" })) });
  // Month view (AOS-304): every day of this month, and all of last month for the comparison line.
  const monthSeries = useQuery({ queryKey: ["analytics", "series", range.from, range.to], enabled: isMonth, queryFn: () => getData<SeriesData>(analyticsPath("series", range.from, range.to, { bucket: "day" })) });
  const lastMonthSeries = useQuery({ queryKey: ["analytics", "series", prevMonth.from, prevMonth.to], enabled: isMonth, queryFn: () => getData<SeriesData>(analyticsPath("series", prevMonth.from, prevMonth.to, { bucket: "day" })) });
  const categories = useQuery({ queryKey: ["analytics", "categories", ...key], queryFn: () => getData<{ categories: CategoryRow[] }>(analyticsPath("categories", range.from, range.to)) });
  const merchants = useQuery({ queryKey: ["analytics", "merchants", ...key], queryFn: () => getData<{ merchants: MerchantRow[] }>(analyticsPath("merchants", range.from, range.to, { limit: "5" })) });
  const latest = useQuery({ queryKey: ["transactions", "latest", ...key], queryFn: () => getData<Transaction[]>(`/api/v1/transactions?${new URLSearchParams({ from: range.from, to: range.to, limit: "5" })}`) });
  const bills = useQuery({ queryKey: ["bills", "upcoming"], queryFn: () => getData<Bill[]>("/api/v1/bills/upcoming") });
  const reviews = useQuery({ queryKey: ["suggestions", "count"], queryFn: () => getData<unknown[]>("/api/v1/transactions/suggestions"), staleTime: 60_000 });
  const attention = useQuery({ queryKey: ["dashboard", "home"], queryFn: () => getData<DashboardHome>("/api/v1/dashboard/home") });

  const nothingYet = summary.data && latest.data && summary.data.spendCount === 0 && summary.data.totals.received === "0.00" && latest.data.length === 0;
  const anomalies = (attention.data?.actionItems ?? []).filter((a) => a.type === "anomaly");
  const dueSoon = (bills.data ?? []).filter((b) => b.status !== "paid" && b.dueDate.slice(0, 10) <= addDays(today, 7));

  return (
    <div className="space-y-4">
      <PeriodSwitcher period={period} today={today} />
      {notice && <p role="status" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">{notice}</p>}
      <Link href="/ask" className="block rounded-xl border border-line bg-surface px-4 py-3 text-sm hover:bg-page">
        Ask your money <span className="text-ink-muted">· “How much on Zepto last month?”</span>
      </Link>

      <div className="rounded-xl border border-line bg-surface p-4">
        {summary.isPending || compared.isPending ? (
          <Skeleton lines={3} />
        ) : summary.error || compared.error ? (
          <ErrorLine message={errorMessage(summary.error ?? compared.error)} onRetry={() => { summary.refetch(); compared.refetch(); }} />
        ) : (
          <Hero
            spent={summary.data!.totals.spent}
            received={summary.data!.totals.received}
            net={summary.data!.totals.net}
            comparedSpent={compared.data!.totals.spent}
            comparedLabel={compare.label}
            current={isCurrent(period, today)}
          />
        )}
      </div>

      <ReviewBanner count={reviews.data?.length ?? 0} />

      {/* Month-end forecast (AOS-714): only for the month in progress, and not before there's anything at all. */}
      {isMonth && isCurrent(period, today) && !nothingYet && <ForecastCard />}

      {nothingYet ? (
        <EmptyState period={period} today={today} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {isMonth ? (
            <>
              <Section title="Running total" error={monthSeries.error || lastMonthSeries.error ? errorMessage(monthSeries.error ?? lastMonthSeries.error) : null} onRetry={() => { monthSeries.refetch(); lastMonthSeries.refetch(); }}>
                {monthSeries.data && lastMonthSeries.data && summary.data && compared.data ? (
                  <CumulativeLine
                    current={cumulativeByDay(monthSeries.data.points)}
                    previous={cumulativeByDay(lastMonthSeries.data.points)}
                    todayDay={isCurrent(period, today) ? Number(today.slice(8, 10)) : null}
                    sentence={runningSentence(summary.data.totals.spent, compared.data.totals.spent, range.to, compare.label, isCurrent(period, today))}
                    currentLabel={describePeriod(period, today)}
                    previousLabel={describePeriod({ kind: "month", anchor: prevMonth.from }, today)}
                  />
                ) : <Skeleton />}
              </Section>
              <Section title="Day by day" error={monthSeries.error ? errorMessage(monthSeries.error) : null} onRetry={() => monthSeries.refetch()}>
                {monthSeries.data ? <DailyBars points={monthSeries.data.points} today={today} title="Spending each day" /> : <Skeleton />}
              </Section>
            </>
          ) : (
            <Section title="This week" error={series.error ? errorMessage(series.error) : null} onRetry={() => series.refetch()}>
              {series.data ? <DailyBars points={series.data.points} today={today} title="Spending this week" /> : <Skeleton />}
            </Section>
          )}

          <Section title="Where it went" action={<Link href="/spending" className="text-xs text-accent">All ›</Link>} error={categories.error ? errorMessage(categories.error) : null} onRetry={() => categories.refetch()}>
            {categories.data ? (categories.data.categories.length ? <CategoryBars rows={categories.data.categories} limit={5} /> : <Quiet>Nothing spent in this period.</Quiet>) : <Skeleton />}
          </Section>

          <LeaksHomeCard range={range} />

          <Section title="Top merchants" error={merchants.error ? errorMessage(merchants.error) : null} onRetry={() => merchants.refetch()}>
            {merchants.data ? (merchants.data.merchants.length ? <MerchantList rows={merchants.data.merchants} /> : <Quiet>No merchants in this period.</Quiet>) : <Skeleton />}
          </Section>

          <Section title="Latest" action={<Link href="/transactions" className="text-xs text-accent">Activity ›</Link>} error={latest.error ? errorMessage(latest.error) : null} onRetry={() => latest.refetch()}>
            {latest.data ? (latest.data.length ? <ul className="divide-y divide-line">{latest.data.map((t) => <FeedRow key={t.id} txn={t} zone={zone} />)}</ul> : <Quiet>No transactions in this period.</Quiet>) : <Skeleton />}
          </Section>
        </div>
      )}

      {dueSoon.length > 0 && (
        <Section title="Coming up">
          <ul className="space-y-1 text-sm">
            {dueSoon.map((b) => (
              <li key={b.id} className="flex justify-between">
                <span>{b.billerName}</span>
                <span className="tabular">{formatInr(b.amount)} · {dueIn(b.dueDate.slice(0, 10), today)}</span>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {anomalies.length > 0 && (
        <Section title="Needs attention">
          <ul className="space-y-2 text-sm">
            {anomalies.map((a) => (
              <li key={a.id}>
                <Link href={`/anomalies/${a.entityId}`} className="flex justify-between">
                  <span><span className="font-medium">{a.title}</span> — {a.description}</span>
                  <span aria-hidden>›</span>
                </Link>
              </li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  );
}

const DAY = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "UTC" });
const formatDay = (d: string) => DAY.format(new Date(`${d}T00:00:00Z`));

/** One sentence for the running-total chart (its text alternative). */
function runningSentence(spent: string, comparedSpent: string, to: string, comparedLabel: string, current: boolean) {
  const d = delta(spent, comparedSpent);
  const change = d.direction === "same" ? `the same as ${comparedLabel}` : `${d.text} than ${comparedLabel}`;
  return `Spent ${formatInr(spent)} ${current ? `by ${formatDay(to)}` : "this month"}; ${change}.`;
}
function addDays(d: string, n: number) {
  return new Date(new Date(`${d}T00:00:00Z`).getTime() + n * 86_400_000).toISOString().slice(0, 10);
}
function dueIn(due: string, today: string) {
  const days = Math.round((Date.parse(due) - Date.parse(today)) / 86_400_000);
  return days < 0 ? `${-days}d overdue` : days === 0 ? "due today" : days === 1 ? "tomorrow" : `in ${days} days`;
}

function Quiet({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-ink-muted">{children}</p>;
}

function ErrorLine({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <p role="alert" className="text-sm text-negative">
      {message}{" "}
      <button type="button" onClick={onRetry} className="font-medium underline">Try again</button>
    </p>
  );
}

function EmptyState({ period, today }: { period: Period; today: string }) {
  if (!isCurrent(period, today)) {
    return <Section title="Nothing here"><Quiet>Nothing spent or received in this period. Use ◀ to look further back.</Quiet></Section>;
  }
  return (
    <Section title="No transactions yet">
      <p className="mb-3 text-sm text-ink-muted">Bring your money in — any of these works:</p>
      <ul className="grid gap-2 sm:grid-cols-3">
        <li><Link href="/import" className="block rounded-lg border border-line px-3 py-3 text-sm hover:bg-page">Import a bank statement</Link></li>
        <li><Link href="/settings" className="block rounded-lg border border-line px-3 py-3 text-sm hover:bg-page">Connect your SMS</Link></li>
        <li><span className="block rounded-lg border border-line px-3 py-3 text-sm text-ink-muted">Add cash with <strong className="text-ink">+ Cash</strong></span></li>
      </ul>
    </Section>
  );
}

export default function Home() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={4} />}>
        <HomeContent />
      </Suspense>
    </AppShell>
  );
}
