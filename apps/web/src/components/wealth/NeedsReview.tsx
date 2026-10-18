"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { errorMessage, getData } from "@/lib/query";
import { dayLabel, RECONCILIATION_PATH, type HoldingStatus } from "@/lib/wealth";

/** Holdings whose units don't add up (AOS-805): what each statement said, what the ledger gives, and why. */
export function BlockedList({ items }: { items: HoldingStatus[] }) {
  return (
    <ul className="space-y-2">
      {items.map((h) => (
        <li key={`${h.folio}:${h.scheme}:${h.statementDate}`} aria-label={`${h.scheme} needs review`} className="rounded-lg bg-page px-3 py-2 text-sm">
          <p className="font-medium text-ink">{h.scheme}</p>
          <p className="text-xs text-ink-muted">{h.amc} · Folio {h.folio} · statement of {dayLabel(h.statementDate)}</p>
          <p className="mt-1 text-sm text-ink">{h.reason}</p>
          <p className="text-xs text-ink-muted">Statement: {h.statementUnits} units · your transactions: {h.ledgerUnits} units</p>
        </li>
      ))}
    </ul>
  );
}

/**
 * The Review tab's investments block (AOS-805/820): holdings blocked by reconciliation. They are left out of the
 * Wealth totals until they add up; nothing shows when every holding reconciles.
 */
export default function WealthReviewSection() {
  const q = useQuery({ queryKey: ["wealth", "reconciliation"], queryFn: () => getData<{ holdings: HoldingStatus[]; blocked: number }>(RECONCILIATION_PATH) });
  if (q.isPending) return null;
  if (q.error) {
    return (
      <p role="alert" className="text-sm text-negative">
        Investments to review couldn&apos;t load: {errorMessage(q.error)}{" "}
        <button type="button" className="underline" onClick={() => q.refetch()}>Try again</button>
      </p>
    );
  }
  const blocked = q.data.holdings.filter((h) => h.status === "blocked");
  if (blocked.length === 0) return null;
  return (
    <section aria-label="Investments that don't add up" className="rounded-xl border border-line bg-surface p-4">
      <h3 className="text-sm font-semibold text-ink">Investments that don&apos;t add up ({blocked.length})</h3>
      <p className="mb-3 text-xs text-ink-muted">These are left out of your Wealth totals until a statement makes them add up.</p>
      <BlockedList items={blocked} />
      <Link href="/wealth/import" className="mt-3 inline-block text-sm text-accent underline">Import another statement</Link>
    </section>
  );
}
