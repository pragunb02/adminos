"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { errorMessage, getData, postData } from "@/lib/query";

/** A message format AdminOS learnt from readings you confirmed (AOS-706, GET /api/v1/review/templates). */
export interface MessageTemplate {
  id: string;
  channel: "sms" | "whatsapp" | "gmail";
  bankCode: string | null;
  direction: "debit" | "credit";
  /** The bank's wording with slots such as <AMT>; never any values. */
  shape: string;
  seen: number;
}

export const TEMPLATES_KEY = ["review", "templates"] as const;

/** What each slot stands for, in words. */
const SLOT: Record<string, string> = {
  "<AMT>": "amount", "<BAL>": "balance", "<ACCT>": "last 4", "<DATE>": "date", "<M>": "payee", "<N>": "number", "<NAME>": "name", "<ANY>": "any word",
};
const SLOTS = /(<(?:AMT|BAL|ACCT|DATE|M|N|NAME|ANY)>)/;

/** The shape as text with each slot shown as a labelled chip. Everything is rendered as text, never as HTML. */
export function Shape({ shape }: { shape: string }) {
  return (
    <p className="rounded-lg bg-page px-3 py-2 font-mono text-xs leading-6">
      {shape.split(SLOTS).map((part, i) =>
        SLOT[part] ? <span key={i} className="rounded bg-accent/10 px-0.5 font-sans text-accent">[{SLOT[part]}]</span> : <span key={i}>{part}</span>,
      )}
    </p>
  );
}

/**
 * Review → formats AdminOS can learn (AOS-706, D-107). After you confirm three readings of the same bank format,
 * it is offered here. Approving it means AdminOS reads that format itself from now on, with no model, straight into
 * your totals. Shows nothing when none wait.
 */
export default function LearnFormats() {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: TEMPLATES_KEY, queryFn: () => getData<MessageTemplate[]>("/api/v1/review/templates") });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const decide = async (t: MessageTemplate, approve: boolean) => {
    setBusy(true);
    setFailure(null);
    setDone(null);
    try {
      await postData(`/api/v1/review/templates/${encodeURIComponent(t.id)}/${approve ? "approve" : "reject"}`, {});
      setDone(approve ? "AdminOS will read this format itself" : "This format will keep going to Review");
    } catch (e) {
      setFailure(`Format: ${errorMessage(e)}`);
    } finally {
      setBusy(false);
      queryClient.invalidateQueries({ queryKey: TEMPLATES_KEY });
    }
  };

  if (list.isPending) return null;
  if (list.error) {
    return (
      <p role="alert" className="text-sm text-negative">
        Formats to learn: {errorMessage(list.error)} <button type="button" className="underline" onClick={() => list.refetch()}>Try again</button>
      </p>
    );
  }
  const items = list.data ?? [];
  if (items.length === 0 && !done && !failure) return null;

  return (
    <section aria-labelledby="learn-formats" className="space-y-3">
      <h3 id="learn-formats" className="text-base font-semibold">Formats AdminOS can learn</h3>
      <p className="text-sm text-ink-muted">
        You confirmed these bank formats several times. Approve one and AdminOS reads it itself from now on: no model, and straight into your totals.
      </p>
      {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}
      {done && <p role="status" className="text-sm text-ink-muted">{done}</p>}
      <ul className="space-y-3">
        {items.map((t) => (
          <li key={t.id} aria-label={`${t.direction === "credit" ? "Money in" : "Money out"} format seen ${t.seen} times`} className="rounded-xl border border-line bg-surface p-4">
            <Shape shape={t.shape} />
            <p className="mt-1 text-xs text-ink-muted">
              {t.direction === "credit" ? "Money in" : "Money out"} · {t.channel === "whatsapp" ? "WhatsApp" : t.channel === "gmail" ? "Email" : "SMS"}{t.bankCode ? ` · ${t.bankCode}` : ""} · seen {t.seen} times
            </p>
            <div className="mt-3 flex gap-2">
              <button type="button" disabled={busy} onClick={() => decide(t, true)}
                className="h-11 flex-1 rounded-lg bg-accent text-sm font-semibold text-white disabled:opacity-50">Read it myself</button>
              <button type="button" disabled={busy} onClick={() => decide(t, false)}
                className="h-11 flex-1 rounded-lg border border-line text-sm disabled:opacity-50">Keep reviewing</button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
