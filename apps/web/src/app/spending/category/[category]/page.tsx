"use client";

import Link from "next/link";
import { Suspense } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import PeriodSwitcher from "@/components/PeriodSwitcher";
import Section, { Skeleton } from "@/components/console/Section";
import TrendLine from "@/components/console/TrendLine";
import MerchantTable from "@/components/console/MerchantTable";
import FeedRow from "@/components/console/FeedRow";
import { analyticsPath, categoryLabel, type CategoryRow, type MerchantRow, type SeriesData } from "@/lib/analytics";
import { CATEGORIES, periodOnly, useConsolePeriod } from "@/lib/consolePeriod";
import { arrow, delta } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";
import type { Transaction } from "@/lib/types";

/** The first day of the month `n` months before `day`'s month. */
function monthsBefore(day: string, n: number) {
  const d = new Date(`${day}T00:00:00Z`);
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - n, 1)).toISOString().slice(0, 10);
}

/** One category (AOS-305): this period vs the same days before, 6-month trend, merchants, transactions. */
function CategoryContent() {
  const category = String(useParams().category ?? "");
  const { search, zone, today, period, notice, range, compare } = useConsolePeriod();
  const keep = periodOnly(search);
  const known = (CATEGORIES as readonly string[]).includes(category);
  const trendFrom = monthsBefore(range.to, 5);

  const now = useQuery({ queryKey: ["analytics", "categories", range.from, range.to], enabled: known, queryFn: () => getData<{ categories: CategoryRow[] }>(analyticsPath("categories", range.from, range.to)) });
  const before = useQuery({ queryKey: ["analytics", "categories", compare.from, compare.to], enabled: known, queryFn: () => getData<{ categories: CategoryRow[] }>(analyticsPath("categories", compare.from, compare.to)) });
  const trend = useQuery({ queryKey: ["analytics", "series", "month", category, trendFrom, range.to], enabled: known, queryFn: () => getData<SeriesData>(analyticsPath("series", trendFrom, range.to, { bucket: "month", category })) });
  const merchants = useQuery({ queryKey: ["analytics", "merchants", category, range.from, range.to], enabled: known, queryFn: () => getData<{ merchants: MerchantRow[] }>(analyticsPath("merchants", range.from, range.to, { category, limit: "5" })) });
  const txns = useQuery({ queryKey: ["transactions", "category", category, range.from, range.to], enabled: known, queryFn: () => getData<Transaction[]>(`/api/v1/transactions?${new URLSearchParams({ category, from: range.from, to: range.to, limit: "10" })}`) });

  const back = <Link href={`/spending${keep}`} className="inline-flex min-h-11 items-center pr-3 text-sm text-accent">← Spending</Link>;
  if (!known) {
    return (
      <div className="space-y-3">
        {back}
        <h2 className="text-xl font-bold">No such category</h2>
        <p className="text-sm text-ink-muted">&ldquo;{category}&rdquo; isn&apos;t a category. Pick one from Spending.</p>
      </div>
    );
  }
  const spentNow = now.data?.categories.find((c) => c.category === category)?.spent ?? "0.00";
  const spentBefore = before.data?.categories.find((c) => c.category === category)?.spent ?? "0.00";
  const d = delta(spentNow, spentBefore);

  return (
    <div className="space-y-4">
      {back}
      <PeriodSwitcher period={period} today={today} />
      {notice && <p role="status" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">{notice}</p>}
      <div className="rounded-xl border border-line bg-surface p-4">
        <h2 className="text-sm text-ink-muted">{categoryLabel(category)}</h2>
        {now.isPending || before.isPending ? <Skeleton lines={2} /> : now.error || before.error ? (
          <p role="alert" className="text-sm text-negative">{errorMessage(now.error ?? before.error)} <button type="button" className="underline" onClick={() => { now.refetch(); before.refetch(); }}>Try again</button></p>
        ) : (
          <>
            <p className="tabular text-3xl font-semibold">{formatInr(spentNow)}</p>
            <p className="text-sm text-ink-muted">{d.direction === "same" ? `Same as ${compare.label}` : <><span aria-hidden>{arrow(d.direction)} </span>{d.text} than {compare.label}</>}</p>
          </>
        )}
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Section title="Last 6 months" error={trend.error ? errorMessage(trend.error) : null} onRetry={() => trend.refetch()}>
          {trend.data ? <TrendLine points={trend.data.points} bucket="month" title={`${categoryLabel(category)} by month`} /> : <Skeleton />}
        </Section>
        <Section title="Top merchants" error={merchants.error ? errorMessage(merchants.error) : null} onRetry={() => merchants.refetch()}>
          {merchants.data ? (merchants.data.merchants.length ? <MerchantTable rows={merchants.data.merchants} /> : <p className="text-sm text-ink-muted">No merchants in this period.</p>) : <Skeleton />}
        </Section>
      </div>
      <Section title="Transactions" action={<Link href={`/transactions?category=${category}`} className="text-xs text-accent">All in Activity ›</Link>} error={txns.error ? errorMessage(txns.error) : null} onRetry={() => txns.refetch()}>
        {txns.data ? (txns.data.length ? <ul className="divide-y divide-line">{txns.data.map((t) => <FeedRow key={t.id} txn={t} zone={zone} />)}</ul> : <p className="text-sm text-ink-muted">No transactions in this period.</p>) : <Skeleton />}
      </Section>
    </div>
  );
}

export default function CategoryPage() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={4} />}>
        <CategoryContent />
      </Suspense>
    </AppShell>
  );
}
