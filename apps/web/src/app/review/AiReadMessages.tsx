"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { browserZone, relativeDate } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { errorMessage, getData, postData } from "@/lib/query";

/** A transaction the AI reader found in a bank message no parser recognised (AOS-705, GET /api/v1/review/messages). */
export interface MessageProposal {
  id: string;
  channel: "sms" | "whatsapp" | "gmail";
  direction: "debit" | "credit";
  amount: string;
  accountLast4: string | null;
  merchant: string | null;
  bankCode: string | null;
  occurredAt: string;
  /** The model that read it. */
  readBy: string;
}

const CHANNEL: Record<MessageProposal["channel"], string> = { sms: "SMS", whatsapp: "WhatsApp", gmail: "Email" };
export const MESSAGES_KEY = ["review", "messages"] as const;

/**
 * Review → messages read by AI (AOS-705, D-106). Each one is a payment a model read from a bank message, checked
 * against the message's own words, but **not in your totals** until you say so. Shows nothing when none wait.
 * Merchant names come from messages, so they are untrusted: shown only as text (React escapes them).
 */
export default function AiReadMessages() {
  const queryClient = useQueryClient();
  const zone = browserZone();
  const list = useQuery({ queryKey: MESSAGES_KEY, queryFn: () => getData<MessageProposal[]>("/api/v1/review/messages") });
  const [busy, setBusy] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const decide = async (p: MessageProposal, confirm: boolean) => {
    setBusy(p.id);
    setFailure(null);
    setDone(null);
    try {
      await postData(`/api/v1/review/messages/${encodeURIComponent(p.id)}/${confirm ? "confirm" : "dismiss"}`, {});
      setDone(confirm ? "Added to your money" : "Left out of your totals");
    } catch (e) {
      setFailure(`${label(p)}: ${errorMessage(e)}`);
    } finally {
      setBusy(null);
      for (const key of [MESSAGES_KEY, ["analytics"], ["transactions"]]) queryClient.invalidateQueries({ queryKey: key });
    }
  };

  if (list.isPending) return null; // the page's own skeleton covers the first paint
  if (list.error) {
    return (
      <p role="alert" className="text-sm text-negative">
        Messages read by AI: {errorMessage(list.error)} <button type="button" className="underline" onClick={() => list.refetch()}>Try again</button>
      </p>
    );
  }
  const items = list.data ?? [];
  if (items.length === 0 && !done && !failure) return null;

  return (
    <section aria-labelledby="ai-read-messages" className="space-y-3">
      <h3 id="ai-read-messages" className="text-base font-semibold">Messages read by AI</h3>
      <p className="text-sm text-ink-muted">
        No parser knew these bank messages, so a model read them. Check each against the message on your phone; nothing counts until you add it.
      </p>
      {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}
      {done && <p role="status" className="text-sm text-ink-muted">{done}</p>}
      <ul className="space-y-3">
        {items.map((p) => (
          <li key={p.id} aria-label={label(p)} className="rounded-xl border border-line bg-surface p-4">
            <p className="flex justify-between gap-2 text-sm">
              <span className="truncate font-semibold">{p.merchant ?? "Unknown payee"}</span>
              <span className="tabular">{p.direction === "credit" ? "+" : "−"}{formatInr(p.amount, { paise: true })}</span>
            </p>
            <p className="text-xs text-ink-muted">
              {relativeDate(p.occurredAt, zone)} · {CHANNEL[p.channel]}{p.accountLast4 ? ` · ··${p.accountLast4}` : ""} · read by {p.readBy}
            </p>
            <div className="mt-3 flex gap-2">
              <button type="button" disabled={busy !== null} onClick={() => decide(p, true)}
                className="h-11 flex-1 rounded-lg bg-accent text-sm font-semibold text-white disabled:opacity-50">Add to my money</button>
              <button type="button" disabled={busy !== null} onClick={() => decide(p, false)}
                className="h-11 flex-1 rounded-lg border border-line text-sm disabled:opacity-50">Not a payment</button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

const label = (p: MessageProposal) => `${p.direction === "credit" ? "Money in" : "Money out"} read by AI: ${p.merchant ?? "unknown payee"}`;
