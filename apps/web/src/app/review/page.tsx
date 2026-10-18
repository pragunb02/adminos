"use client";

import { Suspense, useCallback } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import { Skeleton } from "@/components/console/Section";
import UndoToast from "@/components/console/UndoToast";
import PayLaterGaps from "@/components/PayLaterGaps";
import SplitwiseReview from "@/components/SplitwiseReview";
import MerchantMergeReview from "@/components/review/MerchantMergeReview";
import { categoryLabel } from "@/lib/analytics";
import { browserZone, relativeDate } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { answerQuestion, answeredMessage, goldenNote } from "@/lib/categoryReview";
import { errorMessage, getData, postData } from "@/lib/query";
import type { CategoryQuestion, Transaction } from "@/lib/types";
import CategoryQuestions from "./CategoryQuestions";
import { usePendingAction } from "./usePendingAction";
import AiReadMessages from "./AiReadMessages";
import LearnFormats from "./LearnFormats";

export interface Suggestion {
  id: string;
  relation: "duplicate" | "transfer" | "refund" | "reversal";
  score: number;
  reason: string;
  primary: Transaction;
  related: Transaction;
}

const TABS = [
  { key: "", label: "All", relations: ["duplicate", "transfer", "refund", "reversal"] },
  { key: "duplicate", label: "Duplicates", relations: ["duplicate"] },
  { key: "transfer", label: "Transfers", relations: ["transfer"] },
  { key: "refund", label: "Refunds", relations: ["refund", "reversal"] },
] as const;

/** Plain-language question and answers per kind (D-49). */
const COPY: Record<Suggestion["relation"], { title: string; yes: string; no: string; done: (yes: boolean) => string }> = {
  duplicate: { title: "Possible duplicate", yes: "Same payment", no: "Different", done: (y) => (y ? "Merged into one payment" : "Kept as two payments") },
  transfer: { title: "Transfer between your accounts?", yes: "Yes, a transfer", no: "No", done: (y) => (y ? "Marked as a transfer" : "Kept as spending and income") },
  refund: { title: "A refund of this purchase?", yes: "Yes, a refund", no: "No", done: (y) => (y ? "Marked as a refund" : "Kept separate") },
  reversal: { title: "A failed payment, reversed?", yes: "Yes, reversed", no: "No", done: (y) => (y ? "Marked as a reversal" : "Kept separate") },
};

/** How long the Undo window is; the decision is sent when it ends (confirming a duplicate deletes a row). */
const UNDO_MS = 5000;

/**
 * Review (AOS-309, M/Review): confirm or reject suggested duplicates, transfers, refunds and reversals, and (AOS-708)
 * check the categories AdminOS is least sure of. Data quality before insight: until decided, these keep the totals
 * provisional (D-49). Every answer waits 5 s for Undo before it is sent.
 */
function ReviewContent() {
  const router = useRouter();
  const pathname = usePathname();
  const kind = useSearchParams().get("kind") ?? "";
  const tab = TABS.find((t) => t.key === kind) ?? TABS[0];
  const zone = browserZone();
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ["suggestions", "list"], queryFn: () => getData<Suggestion[]>("/api/v1/transactions/suggestions") });

  // A decision (a pair, or a category answer) waits UNDO_MS for Undo: hidden at once, sent when the window ends.
  const settle = useCallback(() => {
    for (const key of [["suggestions"], ["analytics"], ["transactions"], ["categoryReview"]]) queryClient.invalidateQueries({ queryKey: key });
  }, [queryClient]);
  const { pending, failure, note, start, timeUp, undo } = usePendingAction(settle);

  const decide = (s: Suggestion, yes: boolean) => start({
    key: `pair:${s.id}`,
    message: COPY[s.relation].done(yes),
    label: COPY[s.relation].title,
    send: () => postData(`/api/v1/transactions/suggestions/${s.id}/${yes ? "confirm" : "reject"}`, {}).then(() => null),
  });
  const answer = (q: CategoryQuestion, category: string, keepCopy: boolean) => start({
    key: `category:${q.transaction.id}`,
    message: answeredMessage(q, category),
    label: `${q.transaction.merchantName || "This payee"}`,
    send: () => answerQuestion(q.transaction.id, category, keepCopy).then(goldenNote),
  });

  const hiddenPair = pending?.key.startsWith("pair:") ? pending.key.slice("pair:".length) : undefined;
  const hiddenQuestion = pending?.key.startsWith("category:") ? pending.key.slice("category:".length) : undefined;
  const visible = (list.data ?? []).filter((s) => (tab.relations as readonly string[]).includes(s.relation) && s.id !== hiddenPair);
  const counts = (relations: readonly string[]) => (list.data ?? []).filter((s) => relations.includes(s.relation) && s.id !== hiddenPair).length;
  const go = (key: string) => router.push(`${pathname}${key ? `?kind=${key}` : ""}`, { scroll: false });

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold">Review</h2>
      {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}
      {note && <p aria-live="polite" className="text-sm text-ink-muted">{note}</p>}
      <CategoryQuestions hiddenId={hiddenQuestion} onAnswer={answer} />
      <p className="text-sm text-ink-muted">These pairs look related. Your answers make the totals final.</p>
      <PayLaterGaps />
      <AiReadMessages />
      <LearnFormats />
      <div role="group" aria-label="Kind" className="flex flex-wrap gap-1 rounded-lg bg-line p-0.5">
        {TABS.map((t) => (
          <button key={t.label} type="button" aria-pressed={tab.key === t.key} onClick={() => go(t.key)}
            className={`min-h-9 flex-1 rounded-md px-3 text-sm ${tab.key === t.key ? "bg-surface font-semibold shadow-xs" : "text-ink-muted"}`}>
            {t.label} <span className="tabular text-xs text-ink-muted">({counts(t.relations)})</span>
          </button>
        ))}
      </div>
      {list.isPending ? (
        <Skeleton lines={5} />
      ) : list.error ? (
        <p role="alert" className="text-sm text-negative">{errorMessage(list.error)} <button type="button" className="underline" onClick={() => list.refetch()}>Try again</button></p>
      ) : visible.length === 0 ? (
        <p className="rounded-xl border border-line bg-surface p-6 text-center text-sm text-ink-muted">
          {counts(TABS[0].relations) === 0 ? "All clear — your totals are final." : `Nothing to review in ${tab.label}.`}
        </p>
      ) : (
        <ul className="space-y-3">
          {visible.map((s) => (
            <li key={s.id} className="rounded-xl border border-line bg-surface p-4" aria-label={COPY[s.relation].title}>
              <p className="text-sm font-semibold">{COPY[s.relation].title}</p>
              <div className="mt-2 grid gap-2 sm:grid-cols-2">
                <Side t={s.primary} zone={zone} />
                <Side t={s.related} zone={zone} />
              </div>
              <p className="mt-2 text-xs text-ink-muted">{s.reason} · score {s.score}</p>
              <div className="mt-3 flex gap-2">
                <button type="button" onClick={() => decide(s, true)} className="h-11 flex-1 rounded-lg bg-accent text-sm font-semibold text-white">{COPY[s.relation].yes}</button>
                <button type="button" onClick={() => decide(s, false)} className="h-11 flex-1 rounded-lg border border-line text-sm">{COPY[s.relation].no}</button>
              </div>
            </li>
          ))}
        </ul>
      )}
      <SplitwiseReview />
      {/* Look-alike merchant names (AOS-709): its own section and Undo, separate from the pair queue. */}
      <MerchantMergeReview />
      {pending && <UndoToast key={pending.key} message={pending.message} ms={UNDO_MS} onUndo={undo} onClose={timeUp} />}
    </div>
  );
}

function Side({ t, zone }: { t: Transaction; zone: string }) {
  return (
    <div className="rounded-lg bg-page px-3 py-2 text-sm">
      <p className="flex justify-between gap-2">
        <span className="truncate">{t.merchantName || "Unknown"}</span>
        <span className="tabular">{t.type === "credit" ? "+" : "−"}{formatInr(t.amount)}</span>
      </p>
      <p className="text-xs text-ink-muted">
        {relativeDate(t.transactedAt, zone)} · {t.sourceType}{t.accountLast4 ? ` · ··${t.accountLast4}` : ""} · {categoryLabel(t.category)}
      </p>
    </div>
  );
}

export default function ReviewPage() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={4} />}>
        <ReviewContent />
      </Suspense>
    </AppShell>
  );
}
