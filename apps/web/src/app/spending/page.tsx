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
import { analyticsPath, type CategoryRow, type MerchantRow, type SummaryData } from "@/lib/analytics";
import { periodOnly, useConsolePeriod } from "@/lib/consolePeriod";
import { arrow, delta } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";

const VIEWS = [
  { key: "categories", label: "Categories" },
  { key: "merchants", label: "Merchants" },
] as const;
type View = (typeof VIEWS)[number]["key"];

/** Spending (AOS-305, M/Spending): where the money went this period, by category or by merchant. */
function SpendingContent() {
  const router = useRouter();
  const pathname = usePathname();
  const { search, today, period, notice, range, compare } = useConsolePeriod();
  const view: View = search.get("view") === "merchants" ? "merchants" : "categories";
  const key = [range.from, range.to];

  const summary = useQuery({ queryKey: ["analytics", "summary", ...key], queryFn: () => getData<SummaryData>(analyticsPath("summary", range.from, range.to)) });
  const compared = useQuery({ queryKey: ["analytics", "summary", "compare", compare.from, compare.to], queryFn: () => getData<SummaryData>(analyticsPath("summary", compare.from, compare.to)) });
  const categories = useQuery({ queryKey: ["analytics", "categories", ...key], enabled: view === "categories", queryFn: () => getData<{ categories: CategoryRow[] }>(analyticsPath("categories", range.from, range.to)) });
  const merchants = useQuery({ queryKey: ["analytics", "merchants", "all", ...key], enabled: view === "merchants", queryFn: () => getData<{ merchants: MerchantRow[] }>(analyticsPath("merchants", range.from, range.to, { limit: "50" })) });

  const switchTo = (v: View) => {
    const q = new URLSearchParams(search);
    if (v === "categories") q.delete("view"); else q.set("view", v);
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

      {view === "categories" ? (
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
