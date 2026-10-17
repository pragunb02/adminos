"use client";

import Link from "next/link";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Skeleton } from "@/components/console/Section";
import { formatInr } from "@/lib/money";
import { dismissSuggestionPath, LIMITS_PATH, limitText, usedPercent, validAmount, type Limit, type LimitsData, type LimitSuggestion, type NewLimit } from "@/lib/limits";
import { errorMessage, getData, postData } from "@/lib/query";

/** A limit's progress as a bar with its words beside it (D-49: colour is never the only signal). */
export function LimitBar({ limit }: { limit: Limit }) {
  const pct = usedPercent(limit.status.used);
  return (
    <div>
      <div className="flex items-baseline justify-between gap-2 text-sm">
        <span className="text-ink">{limit.label} <span className="text-xs text-ink-muted">{limit.period}</span></span>
        <span className="tabular text-ink">{formatInr(limit.status.spent)} of {formatInr(limit.amount)}</span>
      </div>
      <div className="mt-1 h-2 rounded-full bg-line" role="progressbar" aria-label={`${limit.label} ${limit.period} limit`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.max(0, Math.min(pct, 100))}>
        <div className={`h-2 rounded-full ${pct >= 100 ? "bg-negative" : "bg-accent"}`} style={{ width: `${Math.max(0, Math.min(pct, 100))}%` }} />
      </div>
      <p className="tabular mt-0.5 text-xs text-ink-muted">{limitText(limit)}</p>
    </div>
  );
}

/**
 * The leaks page's limits (AOS-904): "Learning your habits: N of 30 days" during the first month; then the
 * owner's limits with their progress, and suggestions to accept, adjust or put off. Nothing pushes until accepted.
 */
export default function LimitsPanel() {
  const queryClient = useQueryClient();
  const limits = useQuery({ queryKey: ["coach", "limits"], queryFn: () => getData<LimitsData>(LIMITS_PATH) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["coach"] });
  const accept = useMutation({ mutationFn: (v: NewLimit) => postData<Limit>(LIMITS_PATH, v), onSuccess: refresh });
  const dismiss = useMutation({ mutationFn: (lens: string) => postData<unknown>(dismissSuggestionPath(lens), {}), onSuccess: refresh });

  if (limits.isPending) return <Skeleton lines={2} />;
  if (limits.error) {
    return (
      <p role="alert" className="text-sm text-negative">
        {errorMessage(limits.error, "Couldn't load your limits")}{" "}
        <button type="button" onClick={() => limits.refetch()} className="font-medium underline">Try again</button>
      </p>
    );
  }
  const { learning, suggestions } = limits.data;
  const mine = limits.data.limits;
  const error = accept.error ?? dismiss.error;
  return (
    <section aria-label="Limits" className="space-y-3 rounded-xl border border-line bg-surface p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-ink">Limits</h3>
        <Link href="/settings#limits" className="text-xs text-accent">Manage ›</Link>
      </div>
      {!learning.ready ? (
        <div role="status">
          <p className="text-sm text-ink">{learning.label}</p>
          <p className="text-xs text-ink-muted">For the first {learning.daysNeeded} days the coach only watches. After that it suggests limits from your own trends; nothing is limited until you accept one.</p>
        </div>
      ) : (
        <>
          {mine.length > 0 ? (
            <ul className="space-y-3">{mine.map((l) => <li key={l.id}><LimitBar limit={l} /></li>)}</ul>
          ) : suggestions.length === 0 && <p className="text-sm text-ink-muted">No limits yet. Add one in Settings, or wait for a suggestion.</p>}
          {suggestions.map((s) => (
            <SuggestionRow key={s.lens} suggestion={s} busy={accept.isPending || dismiss.isPending}
              onAccept={(amount) => accept.mutate({ scope: { kind: "lens", value: s.lens }, period: s.period, amount })}
              onDismiss={() => dismiss.mutate(s.lens)} />
          ))}
        </>
      )}
      {error && <p role="alert" className="text-sm text-negative">{errorMessage(error, "Couldn't save that")}</p>}
    </section>
  );
}

function SuggestionRow({ suggestion: s, busy, onAccept, onDismiss }: { suggestion: LimitSuggestion; busy: boolean; onAccept: (amount: string) => void; onDismiss: () => void }) {
  const [adjusting, setAdjusting] = useState(false);
  const [amount, setAmount] = useState(s.amount.replace(/\.00$/, ""));
  return (
    <div className="rounded-lg border border-line p-3 text-sm" aria-label={`Suggested limit for ${s.label}`} role="group">
      <p className="text-ink">A suggested limit is ready: <span className="font-semibold">{s.label}</span>, {formatInr(s.amount)} a week.</p>
      <details className="mt-1 text-xs text-ink-muted">
        <summary className="cursor-pointer text-accent">How we got this</summary>
        <p className="mt-1">Your last {s.weeks.length} full weeks: {s.weeks.map((w) => formatInr(w.spent)).join(", ")}.</p>
        <p>Median {formatInr(s.median)}, minus {s.stepPercent}%, rounded down to the nearest {formatInr(s.roundTo)}.</p>
      </details>
      {adjusting ? (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <input aria-label={`Weekly limit for ${s.label}`} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} className="h-10 w-32 rounded-lg border border-line px-2" />
          <button type="button" disabled={busy || !validAmount(amount)} onClick={() => onAccept(amount.trim())} className="h-10 rounded-lg bg-accent px-3 font-semibold text-white disabled:opacity-40">Set limit</button>
          <button type="button" onClick={() => setAdjusting(false)} className="h-10 rounded-lg border border-line px-3">Cancel</button>
        </div>
      ) : (
        <div className="mt-2 flex flex-wrap gap-2">
          <button type="button" disabled={busy} onClick={() => onAccept(s.amount)} className="h-10 rounded-lg bg-accent px-3 font-semibold text-white disabled:opacity-40">Accept</button>
          <button type="button" disabled={busy} onClick={() => setAdjusting(true)} className="h-10 rounded-lg border border-line px-3">Adjust</button>
          <button type="button" disabled={busy} onClick={onDismiss} className="h-10 rounded-lg px-3 text-ink-muted underline">Not now</button>
        </div>
      )}
    </div>
  );
}
