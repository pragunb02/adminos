"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import { Skeleton } from "@/components/console/Section";
import {
  ASK_FEEDBACK_PATH,
  ASK_PATH,
  MAX_QUESTION,
  SUGGESTIONS,
  activityHref,
  askErrorText,
  periodLabel,
  resultLines,
  skippedText,
  stepTitle,
  type AskAnswer,
  type AskStatus,
  type AskStep,
} from "@/lib/ask";
import { ApiError, getData, postData } from "@/lib/query";

const SOURCE_LABEL: Record<AskAnswer["source"], string> = {
  model: "Worded by AI · every number checked against your data",
  template: "Straight from your data",
  declined: "Not something I can look up",
  unanswered: "Couldn't look this up",
};

const errorText = (e: unknown) => (e instanceof ApiError ? askErrorText(e.code, e.message) : "Something went wrong. Try again.");

/**
 * "Ask your money" (AOS-711, D-110): type or tap a question, get a short answer whose every number comes from
 * the console's analytics (D-67), see how it was looked up, and rate it. Calm by design (D-49): one question,
 * one answer, details folded away. All text from the server is rendered as text, never as HTML.
 */
export default function AskPage() {
  const status = useQuery({ queryKey: ["ask", "status"], queryFn: () => getData<AskStatus>(ASK_PATH) });
  const [question, setQuestion] = useState("");
  const [asked, setAsked] = useState("");
  const ask = useMutation({ mutationFn: (q: string) => postData<AskAnswer>(ASK_PATH, { question: q }) });

  const submit = (q: string) => {
    const text = q.trim();
    if (!text || ask.isPending) return;
    setQuestion(text);
    setAsked(text);
    ask.mutate(text);
  };
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    submit(question);
  };

  const off = status.data?.enabled === false;

  return (
    <AppShell>
      <div className="mx-auto max-w-2xl space-y-5">
        <div>
          <h2 className="text-xl font-bold">Ask your money</h2>
          <p className="mt-1 text-sm text-ink-muted">Ask in plain words, in English or Hinglish. Answers use the same numbers as Spending and Activity.</p>
        </div>

        {status.isPending ? (
          <Skeleton lines={2} />
        ) : off ? (
          <p role="status" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
            Ask isn&apos;t turned on for this server yet. It stays off until <code>AI_ASK_ENABLED=true</code> is set with an evaluated model.
          </p>
        ) : null}

        <form onSubmit={onSubmit} className="space-y-2" aria-label="Ask a question">
          <label htmlFor="ask-question" className="block text-sm font-medium">
            Your question
          </label>
          <div className="flex gap-2">
            <input
              id="ask-question"
              type="text"
              value={question}
              maxLength={MAX_QUESTION}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="How much on Zepto last month?"
              autoComplete="off"
              aria-describedby="ask-count"
              disabled={off}
              className="min-w-0 flex-1 rounded-lg border border-line bg-surface px-3 py-2 text-base"
            />
            <button type="submit" disabled={off || ask.isPending || !question.trim()} className="rounded-lg bg-emerald-700 px-4 py-2 font-semibold text-white hover:bg-emerald-800 disabled:opacity-40">
              Ask
            </button>
          </div>
          <p id="ask-count" className="text-xs text-ink-muted">
            {question.length}/{MAX_QUESTION} characters. Phone numbers, emails and card numbers are masked before anything is sent.
          </p>
        </form>

        {!off && (
          <div>
            <h3 className="mb-2 text-sm font-medium text-ink-muted">Try one</h3>
            <ul className="flex flex-wrap gap-2">
              {SUGGESTIONS.map((s) => (
                <li key={s}>
                  <button type="button" onClick={() => submit(s)} disabled={ask.isPending} className="rounded-full border border-line bg-surface px-3 py-1.5 text-sm hover:bg-page disabled:opacity-40">
                    {s}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        <div aria-live="polite">
          {ask.isPending && (
            <p role="status" className="text-sm text-ink-muted">
              Looking it up…
            </p>
          )}
          {ask.isError && (
            <div role="alert" className="space-y-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">
              <p>{errorText(ask.error)}</p>
              <button type="button" onClick={() => submit(asked)} className="font-semibold underline">
                Try again
              </button>
            </div>
          )}
          {ask.isSuccess && <Answer key={ask.data.askId} question={asked} answer={ask.data} />}
        </div>
      </div>
    </AppShell>
  );
}

function Answer({ question, answer }: { question: string; answer: AskAnswer }) {
  return (
    <section aria-labelledby="ask-answer" className="space-y-4 rounded-xl border border-line bg-surface p-4">
      <div>
        <h3 id="ask-answer" className="text-sm font-medium text-ink-muted">
          Answer to “{question}”
        </h3>
        <p className="mt-2 text-lg leading-relaxed">{answer.answer}</p>
        {answer.notice && <p className="mt-2 rounded-lg bg-page px-3 py-2 text-sm">{answer.notice}</p>}
        <p className="mt-2 text-xs text-ink-muted">{SOURCE_LABEL[answer.source]}</p>
      </div>
      {answer.steps.length > 0 && <HowIGotThis steps={answer.steps} />}
      <Feedback askId={answer.askId} />
    </section>
  );
}

function HowIGotThis({ steps }: { steps: AskStep[] }) {
  return (
    <details className="rounded-lg border border-line">
      <summary className="cursor-pointer px-3 py-2 text-sm font-medium">How I got this</summary>
      <ol className="space-y-4 px-3 pb-3">
        {steps.map((s) => {
          const href = activityHref(s);
          const lines = resultLines(s);
          return (
            <li key={s.id} className="space-y-1">
              <p className="text-sm font-medium">
                {stepTitle(s)}
                {s.period && <span className="font-normal text-ink-muted"> · {periodLabel(s.period)}</span>}
              </p>
              {s.skipped ? (
                <p className="text-sm text-ink-muted">{skippedText(s.skipped)}</p>
              ) : (
                <table className="w-full text-sm">
                  <caption className="sr-only">{stepTitle(s)}</caption>
                  <tbody>
                    {lines.map((l, i) => (
                      <tr key={`${l.label}-${i}`} className="border-t border-line">
                        <th scope="row" className="py-1 text-left font-normal">
                          {l.label}
                        </th>
                        <td className="py-1 text-right tabular-nums">{l.value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {href && (
                <Link href={href} className="text-sm text-emerald-800 underline">
                  See these payments in Activity
                </Link>
              )}
            </li>
          );
        })}
      </ol>
    </details>
  );
}

function Feedback({ askId }: { askId: string }) {
  const vote = useMutation({ mutationFn: (helpful: boolean) => postData<{ saved: boolean }>(ASK_FEEDBACK_PATH, { askId, helpful }) });
  const chosen = vote.isSuccess ? vote.variables : null;
  return (
    <div className="space-y-1">
      <div className="flex items-center gap-2" role="group" aria-label="Was this helpful?">
        <span className="text-sm">Was this helpful?</span>
        <button type="button" aria-pressed={chosen === true} disabled={vote.isPending} onClick={() => vote.mutate(true)} className="rounded-lg border border-line px-3 py-1 text-sm aria-pressed:bg-emerald-50">
          <span aria-hidden>👍 </span>Yes
        </button>
        <button type="button" aria-pressed={chosen === false} disabled={vote.isPending} onClick={() => vote.mutate(false)} className="rounded-lg border border-line px-3 py-1 text-sm aria-pressed:bg-red-50">
          <span aria-hidden>👎 </span>No
        </button>
      </div>
      <p className="text-xs text-ink-muted">Rating saves your question (personal details masked) and how it was looked up, to make Ask better. Nothing else.</p>
      {vote.isSuccess && <p role="status" className="text-sm">Thanks, saved.</p>}
      {vote.isError && (
        <p role="alert" className="text-sm text-red-700">
          {errorText(vote.error)}
        </p>
      )}
    </div>
  );
}
