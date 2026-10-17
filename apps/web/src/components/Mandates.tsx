"use client";

import { useQuery } from "@tanstack/react-query";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";
import type { Mandate } from "@/lib/types";

export const RAIL = { upi_autopay: "UPI AutoPay", nach: "e-NACH", card_si: "Card standing instruction", unknown: "Mandate" } as const;
const STATUS = { active: "Active", paused: "Paused", revoked: "Revoked", expired: "Expired", completed: "Completed" } as const;

const day = (iso: string) => new Date(`${iso.slice(0, 10)}T00:00:00Z`).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

function line(m: Mandate): string {
  const parts: string[] = [RAIL[m.rail]];
  if (m.frequency !== "unknown") parts.push(m.frequency.replace(/_/g, " ").replace("as presented", "when billed"));
  if (m.amount) parts.push(`up to ${formatInr(m.amount)}`);
  return parts.join(" · ");
}

/**
 * Settings of money that leaves on its own (AOS-502): UPI AutoPay, e-NACH and card standing
 * instructions found in bank SMS. AdminOS can't revoke them; it says where to.
 */
export default function Mandates() {
  const q = useQuery({ queryKey: ["mandates"], queryFn: () => getData<Mandate[]>("/api/v1/mandates") });
  return (
    <section aria-labelledby="mandates-heading" className="space-y-2">
      <h3 id="mandates-heading" className="font-semibold">AutoPay &amp; mandates</h3>
      {q.isPending ? (
        <p className="text-sm text-ink-muted">Loading mandates…</p>
      ) : q.error ? (
        <p role="alert" className="text-sm text-negative">
          {errorMessage(q.error)} <button type="button" className="underline" onClick={() => q.refetch()}>Try again</button>
        </p>
      ) : q.data.length === 0 ? (
        <p className="rounded-lg border border-line bg-surface p-4 text-sm text-ink-muted">
          No mandates yet. They appear when your bank texts you about a UPI AutoPay, e-NACH or card standing instruction.
        </p>
      ) : (
        <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
          {q.data.map((m) => (
            <li key={m.id} className="space-y-1 p-4" aria-label={m.payee}>
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate font-medium">{m.payee}</p>
                  <p className="text-xs text-ink-muted">{line(m)}</p>
                </div>
                <span className={`shrink-0 text-xs font-semibold ${m.status === "active" ? "text-ink" : "text-ink-muted"}`}>{STATUS[m.status]}</span>
              </div>
              <p className="text-xs text-ink-muted">
                {m.nextDebitOn && <>Next: {day(m.nextDebitOn)}{m.nextDebitAmount && ` · ${formatInr(m.nextDebitAmount)}`}. </>}
                {m.lastDebitAt && <>Last taken {day(m.lastDebitAt)}{m.lastDebitAmount && ` · ${formatInr(m.lastDebitAmount)}`}. </>}
                {m.validUntil && m.status === "active" && <>Valid until {day(m.validUntil)}.</>}
              </p>
              {m.dormant && (
                <p className="text-xs font-semibold text-ink">Nothing taken for two cycles — still needed? Revoke it in your UPI app or bank if not.</p>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
