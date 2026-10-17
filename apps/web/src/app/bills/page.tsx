"use client";

import { Suspense } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import { Skeleton } from "@/components/console/Section";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";
import type { DueItem, DuesMonth } from "@/lib/types";

/** YYYY-MM, or null when the URL holds something else (then this month is shown). */
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

export function shiftMonth(month: string, by: number): string {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

const monthName = (month: string) => new Date(`${month}-01T00:00:00Z`).toLocaleDateString("en-IN", { month: "long", year: "numeric", timeZone: "UTC" });
const dayName = (iso: string) => new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });
const daysBetween = (from: string, to: string) => Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);

/** The words for where an item stands, from the user's own "today" (never the browser clock). */
export function standing(item: DueItem, today: string): { text: string; tone: "done" | "late" | "soon" | "calm" } {
  if (item.kind === "renewal") return { text: item.trial ? "Trial ends" : "Expected", tone: "calm" };
  if (item.status === "paid") return { text: "Paid", tone: "done" };
  const days = daysBetween(today, item.date);
  if (item.status === "overdue" || days < 0) return { text: days < 0 ? `${-days}d overdue` : "Overdue", tone: "late" };
  if (days === 0) return { text: "Due today", tone: "soon" };
  if (days === 1) return { text: "Due tomorrow", tone: "soon" };
  return { text: `Due in ${days}d`, tone: days <= 3 ? "soon" : "calm" };
}

const TONE = { done: "text-positive", late: "font-semibold text-negative", soon: "font-semibold text-ink", calm: "text-ink-muted" } as const;

function describe(item: DueItem): string {
  const kind = item.detail.replace(/_/g, " ");
  if (item.kind === "renewal") return `${kind.charAt(0).toUpperCase()}${kind.slice(1)} ${item.trial ? "trial" : "renewal"} · from your past payments`;
  const min = item.minimumDue && item.minimumDue !== item.amount ? ` · minimum ${formatInr(item.minimumDue)}` : "";
  return `${kind.charAt(0).toUpperCase()}${kind.slice(1)} bill${min}`;
}

function Row({ item, today }: { item: DueItem; today: string }) {
  const s = standing(item, today);
  return (
    <li className="flex items-start justify-between gap-3 py-3">
      <div className="min-w-0">
        <p className="truncate font-medium">{item.name}</p>
        <p className="text-xs text-ink-muted">{describe(item)}</p>
      </div>
      <div className="shrink-0 text-right">
        <p className="tabular font-semibold">{formatInr(item.amount)}</p>
        <p className={`text-xs ${TONE[s.tone]}`}>{s.text}</p>
      </div>
    </li>
  );
}

function DuesContent() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const raw = params.get("m");
  const requested = raw && MONTH.test(raw) ? raw : null;
  const dues = useQuery({
    queryKey: ["dues", requested ?? "current"],
    queryFn: () => getData<DuesMonth>(`/api/v1/dues${requested ? `?month=${requested}` : ""}`),
  });
  const month = dues.data?.month ?? requested;
  const current = dues.data ? dues.data.today.slice(0, 7) : null;
  const go = (m: string) => router.push(m === current ? pathname : `${pathname}?m=${m}`, { scroll: false });

  const byDay = new Map<string, DueItem[]>();
  for (const item of dues.data?.items ?? []) byDay.set(item.date, [...(byDay.get(item.date) ?? []), item]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-xl font-bold">Dues &amp; renewals</h2>
        {month && (
          <div role="group" aria-label="Month" className="flex items-center gap-1">
            <button type="button" aria-label="Previous month" onClick={() => go(shiftMonth(month, -1))} className="h-11 w-11 rounded-md border border-line">‹</button>
            <span className="min-w-32 text-center text-sm font-semibold" aria-live="polite">{monthName(month)}</span>
            <button type="button" aria-label="Next month" onClick={() => go(shiftMonth(month, 1))} className="h-11 w-11 rounded-md border border-line">›</button>
          </div>
        )}
      </div>
      {month && current && month !== current && (
        <button type="button" onClick={() => go(current)} className="text-sm underline">Back to this month</button>
      )}

      {dues.isPending ? (
        <Skeleton lines={5} />
      ) : dues.error ? (
        <p role="alert" className="text-sm text-negative">
          {errorMessage(dues.error)} <button type="button" className="underline" onClick={() => dues.refetch()}>Try again</button>
        </p>
      ) : (
        <>
          <dl aria-label="Totals" className="grid grid-cols-3 gap-2 rounded-xl border border-line bg-surface p-4 text-center">
            <div><dt className="text-xs text-ink-muted">To pay</dt><dd className="tabular text-lg font-bold">{formatInr(dues.data.totals.open)}</dd></div>
            <div><dt className="text-xs text-ink-muted">Paid</dt><dd className="tabular text-lg font-bold">{formatInr(dues.data.totals.paid)}</dd></div>
            <div><dt className="text-xs text-ink-muted">Renewals (expected)</dt><dd className="tabular text-lg font-bold">{formatInr(dues.data.totals.renewals)}</dd></div>
          </dl>

          {dues.data.overdue.length > 0 && (
            <section aria-labelledby="overdue-heading" className="rounded-xl border border-line bg-surface px-4">
              <h3 id="overdue-heading" className="pt-3 text-sm font-semibold text-negative">Overdue from earlier months</h3>
              <ul className="divide-y divide-line">{dues.data.overdue.map((i) => <Row key={`${i.kind}-${i.id}`} item={i} today={dues.data.today} />)}</ul>
            </section>
          )}

          {byDay.size === 0 ? (
            <p className="rounded-xl border border-line bg-surface p-6 text-center text-sm text-ink-muted">Nothing due in {monthName(dues.data.month)}.</p>
          ) : (
            [...byDay.entries()].map(([day, items]) => (
              <section key={day} aria-label={dayName(day)} className="rounded-xl border border-line bg-surface px-4">
                <h3 className="pt-3 text-xs font-semibold uppercase text-ink-muted">
                  {dayName(day)}{day === dues.data.today ? " · today" : ""}
                </h3>
                <ul className="divide-y divide-line">{items.map((i) => <Row key={`${i.kind}-${i.id}-${i.date}`} item={i} today={dues.data.today} />)}</ul>
              </section>
            ))
          )}
        </>
      )}
    </div>
  );
}

/**
 * Dues & renewals (AOS-503): a month of bills and expected subscription renewals, with what's left
 * to pay. The month is in the URL (`?m=YYYY-MM`, D-47), so back/forward/refresh keep it.
 */
export default function DuesPage() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={5} />}>
        <DuesContent />
      </Suspense>
    </AppShell>
  );
}
