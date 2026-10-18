"use client";

import { useId, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Skeleton } from "@/components/console/Section";
import { NO_SALARY_TEXT, type NoSalaryReason } from "@/lib/forecast";
import { formatInr } from "@/lib/money";
import { errorMessage } from "@/lib/query";
import {
  CHOICE_TEXT, checkCorrection, confirmSalary, correctSalary, getSalary, ordinal, SALARY_QUERY_KEY,
  type CorrectionErrors, type SalaryCorrection, type SalaryView,
} from "@/lib/salary";

/**
 * "Your salary" (AOS-722): what the forecast uses as the salary, and a way to confirm it or correct it.
 * Self-contained: it loads its own data and, once saved, refreshes the forecast. The payer comes from bank
 * messages, so it is only ever shown as text.
 */
export default function SalaryConfirm() {
  const queryClient = useQueryClient();
  const q = useQuery({ queryKey: SALARY_QUERY_KEY, queryFn: getSalary });
  const [editing, setEditing] = useState(false);
  const saved = (view: SalaryView) => {
    queryClient.setQueryData(SALARY_QUERY_KEY, view);
    setEditing(false);
    void queryClient.invalidateQueries({ queryKey: SALARY_QUERY_KEY });
    void queryClient.invalidateQueries({ queryKey: ["forecast"] });
  };
  const confirm = useMutation({ mutationFn: confirmSalary, onSuccess: saved });
  const correct = useMutation({ mutationFn: (c: SalaryCorrection) => correctSalary(c), onSuccess: saved });
  const busy = confirm.isPending || correct.isPending;
  const failure = confirm.error ?? correct.error;

  return (
    <section aria-label="Your salary" className="mt-3 rounded-lg border border-line p-3 text-sm">
      <h4 className="font-medium text-ink">Your salary</h4>
      {q.isPending ? <Skeleton lines={1} /> : q.error ? (
        <p role="alert" className="text-negative">
          {errorMessage(q.error, "Couldn't load your salary")}{" "}
          <button type="button" onClick={() => q.refetch()} className="font-medium underline">Try again</button>
        </p>
      ) : (
        <>
          <Summary view={q.data} />
          {!editing && (
            <div className="mt-2 flex flex-wrap gap-2">
              {q.data.detected && !q.data.choice && (
                <button type="button" disabled={busy} onClick={() => { correct.reset(); confirm.mutate(); }}
                  className="h-9 rounded-lg bg-accent px-3 font-semibold text-white disabled:opacity-40">Yes, that&apos;s right</button>
              )}
              <button type="button" disabled={busy} onClick={() => { confirm.reset(); setEditing(true); }}
                className="h-9 rounded-lg border border-line px-3 font-medium text-ink disabled:opacity-40">Correct it</button>
            </div>
          )}
          {editing && (
            <CorrectForm view={q.data} busy={busy} onCancel={() => { correct.reset(); setEditing(false); }} onSave={(c) => correct.mutate(c)} />
          )}
        </>
      )}
      {failure && <p role="alert" className="mt-2 text-negative">{errorMessage(failure, "Couldn't save your salary")}</p>}
    </section>
  );
}

function Summary({ view }: { view: SalaryView }) {
  const { choice, detected } = view;
  if (choice) {
    return (
      <p className="tabular text-ink">
        {salaryText(choice.amount, choice.payer, choice.day)} <span className="text-ink-muted">· {CHOICE_TEXT[choice.kind]}</span>
      </p>
    );
  }
  if (detected) {
    return (
      <p className="tabular text-ink">
        {salaryText(detected.typicalAmount, detected.payer, detected.usualDay)}{" "}
        <span className="text-ink-muted">· found in {detected.credits} credit{detected.credits === 1 ? "" : "s"}. Is that right?</span>
      </p>
    );
  }
  const reason = NO_SALARY_TEXT[view.detectedReason as NoSalaryReason] ?? null;
  return <p className="text-ink-muted">We couldn&apos;t find your salary.{reason ? ` ${reason}` : ""} Correct it to set one.</p>;
}

/** "₹85,000 from ACME TECHNOLOGIES around the 30th"; a payer-less salary says so. */
const salaryText = (amount: string, payer: string | null, day: number) =>
  `${formatInr(amount)} ${payer ? `from ${payer}` : "(no payer name)"} around the ${ordinal(day)}`;

function CorrectForm({ view, busy, onCancel, onSave }: { view: SalaryView; busy: boolean; onCancel: () => void; onSave: (c: SalaryCorrection) => void }) {
  const start = view.choice ?? (view.detected && { payer: view.detected.payer, day: view.detected.usualDay, amount: view.detected.typicalAmount });
  const [payer, setPayer] = useState(start?.payer ?? "");
  const [day, setDay] = useState(start ? String(start.day) : "");
  const [amount, setAmount] = useState(start?.amount ?? "");
  const [errors, setErrors] = useState<CorrectionErrors>({});
  const id = useId();
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const r = checkCorrection({ payer, day, amount });
    if (!r.ok) { setErrors(r.errors); return; }
    setErrors({});
    onSave(r.value);
  };
  const problems = Object.values(errors);
  return (
    <form onSubmit={submit} noValidate aria-label="Correct your salary" className="mt-2 grid gap-2 sm:grid-cols-3">
      <div className="grid gap-1">
        <label htmlFor={`${id}-payer`} className="text-ink-muted">Payer</label>
        <input id={`${id}-payer`} value={payer} onChange={(e) => setPayer(e.target.value)} maxLength={200} autoComplete="off" aria-invalid={!!errors.payer}
          placeholder="Leave empty if it has no name" className="h-10 rounded-lg border border-line px-2" />
      </div>
      <div className="grid gap-1">
        <label htmlFor={`${id}-day`} className="text-ink-muted">Day of month</label>
        <input id={`${id}-day`} value={day} onChange={(e) => setDay(e.target.value)} inputMode="numeric" autoComplete="off" aria-invalid={!!errors.day}
          placeholder="30" className="h-10 rounded-lg border border-line px-2" />
      </div>
      <div className="grid gap-1">
        <label htmlFor={`${id}-amount`} className="text-ink-muted">Amount</label>
        <input id={`${id}-amount`} value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" autoComplete="off" aria-invalid={!!errors.amount}
          placeholder="85000" className="h-10 rounded-lg border border-line px-2" />
      </div>
      {problems.length > 0 && (
        <ul role="alert" className="text-negative sm:col-span-3">{problems.map((p) => <li key={p}>{p}</li>)}</ul>
      )}
      <div className="flex gap-2 sm:col-span-3">
        <button type="submit" disabled={busy} className="h-9 rounded-lg bg-accent px-3 font-semibold text-white disabled:opacity-40">Save</button>
        <button type="button" disabled={busy} onClick={onCancel} className="h-9 rounded-lg border border-line px-3 font-medium text-ink">Cancel</button>
      </div>
    </form>
  );
}
