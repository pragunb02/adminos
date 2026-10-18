"use client";

import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { categoryLabel } from "@/lib/analytics";
import {
  GOLDEN_CSV_URL, GOLDEN_KEY, MODEL_KEY, QUESTIONS_KEY, getGoldenRows, getModelStatus, getQuestions, guessLine, learningLine,
  otherCategories, quickCorrections,
} from "@/lib/categoryReview";
import { browserZone, relativeDate } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { errorMessage } from "@/lib/query";
import type { CategoryQuestion } from "@/lib/types";

/**
 * "Check these categories" (AOS-708): the models' unsure guesses, biggest uncertain spend first. One tap confirms or
 * corrects; the page holds the answer for the Undo window before sending it ([onAnswer]). Hidden when there is nothing to ask.
 */
export default function CategoryQuestions({ hiddenId, onAnswer }: {
  hiddenId?: string;
  onAnswer: (q: CategoryQuestion, category: string, keepCopy: boolean) => void;
}) {
  const headingId = useId();
  const zone = browserZone();
  const [keepCopy, setKeepCopy] = useState(false);
  const list = useQuery({ queryKey: QUESTIONS_KEY, queryFn: getQuestions });
  const model = useQuery({ queryKey: MODEL_KEY, queryFn: getModelStatus });
  const golden = useQuery({ queryKey: GOLDEN_KEY, queryFn: getGoldenRows });

  if (list.isPending) return null;
  if (list.error) {
    return (
      <section aria-labelledby={headingId} className="space-y-2">
        <h3 id={headingId} className="text-base font-semibold">Check these categories</h3>
        <p role="alert" className="text-sm text-negative">{errorMessage(list.error)} <button type="button" className="underline" onClick={() => list.refetch()}>Try again</button></p>
      </section>
    );
  }
  const visible = list.data.filter((q) => q.transaction.id !== hiddenId);
  if (visible.length === 0) return null;
  const learning = model.data ? learningLine(model.data) : null;
  const kept = golden.data?.length ?? 0;

  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <div>
        <h3 id={headingId} className="text-base font-semibold">Check these categories</h3>
        <p className="text-sm text-ink-muted">The guesses AdminOS is least sure of, biggest first. Your answer is remembered for the payee. {learning}</p>
      </div>
      <ul className="space-y-3">
        {visible.map((q) => <Question key={q.transaction.id} q={q} zone={zone} onAnswer={(c) => onAnswer(q, c, keepCopy)} />)}
      </ul>
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" className="mt-1 size-4" checked={keepCopy} onChange={(e) => setKeepCopy(e.target.checked)} />
        <span>Also keep a scrubbed copy in my local test set (shop name and category only; never shared)</span>
      </label>
      {kept > 0 && (
        <p className="text-xs text-ink-muted">
          Local test set: {kept} {kept === 1 ? "row" : "rows"} · <a className="underline" href={GOLDEN_CSV_URL} download>Download CSV</a>
        </p>
      )}
    </section>
  );
}

function Question({ q, zone, onAnswer }: { q: CategoryQuestion; zone: string; onAnswer: (category: string) => void }) {
  const name = q.transaction.merchantName || "Unknown payee";
  const quick = quickCorrections(q.guess);
  const rest = otherCategories(q.guess, quick);
  return (
    <li className="rounded-xl border border-line bg-surface p-4" aria-label={`Category for ${name}`}>
      <p className="flex justify-between gap-2 text-sm">
        <span className="truncate font-semibold">{name}</span>
        <span className="tabular">{q.transaction.type === "credit" || q.transaction.nature === "refund" ? "+" : "−"}{formatInr(q.transaction.amount)}</span>
      </p>
      <p className="text-xs text-ink-muted">{relativeDate(q.transaction.transactedAt, zone)} · {q.transaction.sourceType}</p>
      <p className="mt-2 text-sm">{guessLine(q.guess)}</p>
      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" onClick={() => onAnswer(q.guess.category)} className="h-11 flex-1 rounded-lg bg-accent px-3 text-sm font-semibold text-white">
          Yes, {categoryLabel(q.guess.category)}
        </button>
        {quick.map((c) => (
          <button key={c} type="button" onClick={() => onAnswer(c)} className="h-11 rounded-lg border border-line px-3 text-sm">{categoryLabel(c)}</button>
        ))}
        <select aria-label={`Another category for ${name}`} value="" onChange={(e) => e.target.value && onAnswer(e.target.value)}
          className="h-11 rounded-lg border border-line bg-surface px-2 text-sm">
          <option value="">Something else…</option>
          {rest.map((c) => <option key={c} value={c}>{categoryLabel(c)}</option>)}
        </select>
      </div>
    </li>
  );
}
