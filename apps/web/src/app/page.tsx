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
import DailyBars from "@/components/console/DailyBars";
import CategoryBars from "@/components/console/CategoryBars";
import MerchantList from "@/components/console/MerchantList";
import FeedRow from "@/components/console/FeedRow";
import { analyticsPath, type CategoryRow, type MerchantRow, type SeriesData, type SummaryData } from "@/lib/analytics";
import { browserZone } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { comparisonRange, isCurrent, parsePeriod, rangeOf, todayIn, type Period } from "@/lib/period";
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
  const series = useQuery({ queryKey: ["analytics", "series", week.from, week.to], queryFn: () => getData<SeriesData>(analyticsPath("series", week.from, week.to, { bucket: "day" })) });
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

      {nothingYet ? (
        <EmptyState period={period} today={today} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          <Section title={period.kind === "week" || period.kind === "today" ? "This week" : `Week of ${formatDay(week.from)}`} error={series.error ? errorMessage(series.error) : null} onRetry={() => series.refetch()}>
            {series.data ? <DailyBars points={series.data.points} today={today} title="Spending this week" /> : <Skeleton />}
          </Section>

          <Section title="Where it went" action={<Link href="/spending" className="text-xs text-accent">All ›</Link>} error={categories.error ? errorMessage(categories.error) : null} onRetry={() => categories.refetch()}>
            {categories.data ? (categories.data.categories.length ? <CategoryBars rows={categories.data.categories} limit={5} /> : <Quiet>Nothing spent in this period.</Quiet>) : <Skeleton />}
          </Section>

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
