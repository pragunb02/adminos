"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { categoryLabel } from "@/lib/analytics";
import { browserZone, relativeDate } from "@/lib/format";
import { formatInr, fromPaise, toPaise } from "@/lib/money";
import { errorMessage, getData, postData } from "@/lib/query";
import type { SplitEntry, SplitSummary } from "@/lib/splitwise";
import type { Transaction } from "@/lib/types";

/** Plain-language question per kind of match (D-49). */
const QUESTION: Record<string, string> = {
  adjust_debit: "Your share of a bill you paid?",
  settle_in: "A friend paying you back?",
  settle_out: "You paying a friend back?",
};
const DONE: Record<string, string> = {
  adjust_debit: "your share counts",
  owed_spend: "your share, owed to a friend",
  settle_in: "settle-up received",
  settle_out: "settle-up paid",
};

/**
 * Splitwise in Review (AOS-911, D-111): matches that weren't clear, entries still waiting for a bank record, and
 * what was applied (each can be undone). Descriptions and names come from the owner's own export and render as text.
 */
export default function SplitwiseReview() {
  const client = useQueryClient();
  const zone = browserZone();
  const entries = useQuery({ queryKey: ["splitwise", "entries"], queryFn: () => getData<SplitEntry[]>("/api/v1/splitwise/entries?limit=500") });
  const summary = useQuery({ queryKey: ["splitwise", "summary"], queryFn: () => getData<SplitSummary>("/api/v1/splitwise/summary") });
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const act = async (e: SplitEntry, action: "confirm" | "reject" | "ignore" | "undo") => {
    setBusy(e.id); setFailure(null);
    try { await postData(`/api/v1/splitwise/entries/${e.id}/${action}`, {}); }
    catch (err) { setFailure(errorMessage(err)); }
    finally {
      setBusy(null);
      for (const key of [["splitwise"], ["analytics"], ["transactions"]]) client.invalidateQueries({ queryKey: key });
    }
  };

  if (entries.isPending) return null;
  if (entries.error) {
    return (
      <section aria-labelledby="splitwise-review" className="space-y-2">
        <h3 id="splitwise-review" className="text-lg font-semibold">Splitwise</h3>
        <p role="alert" className="text-sm text-negative">{errorMessage(entries.error)} <button type="button" className="underline" onClick={() => entries.refetch()}>Try again</button></p>
      </section>
    );
  }
  const all = entries.data ?? [];
  if (all.length === 0) return null;
  const suggested = all.filter((e) => e.state === "suggested");
  const waiting = all.filter((e) => e.state === "unmatched");
  const applied = all.filter((e) => e.state === "applied");
  const ignored = all.filter((e) => e.state === "ignored");

  return (
    <section aria-labelledby="splitwise-review" className="space-y-3">
      <h3 id="splitwise-review" className="text-lg font-semibold">Splitwise</h3>
      <Balance summary={summary.data} />
      {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}

      {suggested.length > 0 && (
        <ul aria-label="Splitwise matches to review" className="space-y-3">
          {suggested.map((e) => (
            <li key={e.id} aria-label={QUESTION[e.effect]} className="rounded-xl border border-line bg-surface p-4">
              <p className="text-sm font-semibold">{QUESTION[e.effect]}</p>
              <div className="mt-2 grid gap-2 sm:grid-cols-2">
                <Entry e={e} />
                {e.transaction && <Bank t={e.transaction} zone={zone} />}
              </div>
              {e.reason && <p className="mt-2 text-xs text-ink-muted">{e.reason}</p>}
              <div className="mt-3 flex gap-2">
                <button type="button" disabled={busy === e.id} onClick={() => act(e, "confirm")} className="h-11 flex-1 rounded-lg bg-accent text-sm font-semibold text-white disabled:opacity-50">Yes, that&apos;s it</button>
                <button type="button" disabled={busy === e.id} onClick={() => act(e, "reject")} className="h-11 flex-1 rounded-lg border border-line text-sm disabled:opacity-50">Not this one</button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {waiting.length > 0 && (
        <details className="rounded-xl border border-line bg-surface p-4">
          <summary className="cursor-pointer text-sm font-semibold">Waiting for a bank record ({waiting.length})</summary>
          <p className="mt-1 text-xs text-ink-muted">These match by themselves when that account&apos;s statement or SMS arrives. Ignore one to stop looking.</p>
          <ul aria-label="Splitwise entries waiting for a bank record" className="mt-2 space-y-2">
            {waiting.map((e) => (
              <li key={e.id} aria-label={e.description} className="flex items-center justify-between gap-2">
                <Entry e={e} />
                <button type="button" disabled={busy === e.id} onClick={() => act(e, "ignore")} className="h-9 rounded-lg border border-line px-3 text-sm disabled:opacity-50">Ignore</button>
              </li>
            ))}
          </ul>
        </details>
      )}

      {applied.length + ignored.length > 0 && (
        <details className="rounded-xl border border-line bg-surface p-4">
          <summary className="cursor-pointer text-sm font-semibold">Applied ({applied.length}){ignored.length > 0 ? ` · ignored (${ignored.length})` : ""}</summary>
          <ul aria-label="Applied Splitwise entries" className="mt-2 space-y-2">
            {[...applied, ...ignored].map((e) => (
              <li key={e.id} aria-label={e.description} className="flex items-center justify-between gap-2">
                <Entry e={e} note={e.state === "ignored" ? "ignored" : DONE[e.effect]} />
                <button type="button" disabled={busy === e.id} onClick={() => act(e, "undo")} className="h-9 rounded-lg border border-line px-3 text-sm disabled:opacity-50">
                  {e.state === "ignored" ? "Look again" : "Undo"}
                </button>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

function Balance({ summary }: { summary: SplitSummary | undefined }) {
  const inr = summary?.balances.find((b) => b.currency === "INR");
  if (!inr) return null;
  const n = toPaise(inr.net);
  const text = n > 0n ? `Friends owe you ${formatInr(inr.net)}` : n < 0n ? `You owe friends ${formatInr(fromPaise(-n))}` : "You and your friends are settled up";
  return <p className="text-sm">{text} <span className="text-xs text-ink-muted">(from your Splitwise imports)</span></p>;
}

function Entry({ e, note }: { e: SplitEntry; note?: string }) {
  const amount = e.effect === "adjust_debit" ? `your share ${formatInr(e.ownerShare)} of ${formatInr(e.cost)}` : e.kind === "payment" ? formatInr(e.cost) : `your share ${formatInr(e.ownerShare)}`;
  return (
    <div className="min-w-0 rounded-lg bg-page px-3 py-2 text-sm">
      <p className="truncate">{e.description}{e.counterparty ? ` · ${e.counterparty}` : ""}</p>
      <p className="text-xs text-ink-muted">Splitwise · {e.date} · {amount}{note ? ` · ${note}` : ""}</p>
    </div>
  );
}

function Bank({ t, zone }: { t: Transaction; zone: string }) {
  return (
    <div className="rounded-lg bg-page px-3 py-2 text-sm">
      <p className="flex justify-between gap-2">
        <span className="truncate">{t.merchantName || "Unknown"}</span>
        <span className="tabular">{t.type === "credit" ? "+" : "−"}{formatInr(t.amount)}</span>
      </p>
      <p className="text-xs text-ink-muted">{relativeDate(t.transactedAt, zone)} · {t.sourceType}{t.accountLast4 ? ` · ··${t.accountLast4}` : ""} · {categoryLabel(t.category)}</p>
    </div>
  );
}
