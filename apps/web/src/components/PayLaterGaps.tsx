"use client";

import { useQuery } from "@tanstack/react-query";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";
import type { PayLaterSummary } from "@/lib/types";

/** "3 Nov" from an ISO date, read as a calendar date (no time zone shift). */
function shortDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString("en-IN", { day: "numeric", month: "short", timeZone: "UTC" });
}

/**
 * Pay-later spending that isn't in the totals (AOS-909, D-89). A repayment is a transfer because the
 * purchases are the spending; a purchase that left no message is therefore missing. Rather than guess
 * what it bought, Review says so: "LazyPay bill ₹1,200 — only ₹200 of purchases recorded".
 */
export default function PayLaterGaps() {
  const q = useQuery({ queryKey: ["pay-later"], queryFn: () => getData<PayLaterSummary>("/api/v1/pay-later") });
  if (q.isPending) return null;
  if (q.error) {
    return (
      <p role="alert" className="text-sm text-negative">
        Pay-later: {errorMessage(q.error)} <button type="button" className="underline" onClick={() => q.refetch()}>Try again</button>
      </p>
    );
  }
  const gaps = q.data.providers.flatMap((p) => p.gaps.map((g) => ({ provider: p.provider, ...g })));
  if (gaps.length === 0) return null;
  return (
    <section aria-labelledby="pay-later-gaps" className="space-y-2 rounded-xl border border-line bg-surface p-4">
      <h3 id="pay-later-gaps" className="text-sm font-semibold">Pay-later spending not in your totals</h3>
      {gaps.map((g) => (
        <p key={g.billId} className="text-sm">
          <span className="font-medium">{g.provider}</span> bill {formatInr(g.billAmount)} (due {shortDate(g.dueDate)}): only{" "}
          {formatInr(g.recorded)} of purchases recorded, so {formatInr(g.unrecorded)} isn&apos;t in your spending.
        </p>
      ))}
      <p className="text-xs text-ink-muted">Purchase messages from the provider fill this in; the repayment itself is not counted as spending.</p>
    </section>
  );
}
