"use client";

import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Skeleton } from "@/components/console/Section";
import { currentText, GOALS_PATH, goalPath, goalTitle, streakText, validGoalValue, type Goal, type GoalKind, type NewGoal } from "@/lib/goals";
import { LENSES_PATH, type LensesData } from "@/lib/leaks";
import { formatInr } from "@/lib/money";
import { deleteData, errorMessage, getData, postData } from "@/lib/query";

const short = (d: string) => new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "UTC" }).format(new Date(`${d}T00:00:00Z`));

/** One goal: its target, this period, the past periods as met/missed words, and the no-spend streak. */
export function GoalCard({ goal, onDelete, busy }: { goal: Goal; onDelete?: () => void; busy?: boolean }) {
  const past = goal.periods.filter((p) => !p.current);
  return (
    <div className="text-sm" role="group" aria-label={goalTitle(goal)}>
      <p className="font-medium text-ink">{goalTitle(goal)}</p>
      <p className="tabular text-ink-muted">{currentText(goal)} · {streakText(goal)}</p>
      {past.length > 0 && (
        <ul className="tabular mt-1 flex flex-wrap gap-1 text-xs" aria-label={`${goal.label} past ${goal.period === "weekly" ? "weeks" : "months"}`}>
          {past.map((p) => (
            <li key={p.from} className={`rounded px-1.5 py-0.5 ${p.met ? "bg-blue-50 text-accent" : "bg-line text-ink"}`}>
              {short(p.from)}: {p.met ? "✓ met" : "✗ over"} ({formatInr(p.spent)})
            </li>
          ))}
        </ul>
      )}
      {goal.kind === "cut_percent" && goal.baseline && (
        <p className="text-xs text-ink-muted">From your average of {formatInr(goal.baseline)} {goal.period === "weekly" ? "a week" : "a month"} over the 3 months before {short(goal.startedOn)}.</p>
      )}
      {onDelete && <button type="button" disabled={busy} onClick={onDelete} className="mt-1 text-xs text-ink-muted underline" aria-label={`Delete goal: ${goalTitle(goal)}`}>Delete</button>}
    </div>
  );
}

/** Goals on the leaks page (AOS-907): each goal's progress and streak, and setting a new one. */
export default function GoalsPanel() {
  const queryClient = useQueryClient();
  const goals = useQuery({ queryKey: ["coach", "goals"], queryFn: () => getData<Goal[]>(GOALS_PATH) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["coach"] });
  const create = useMutation({ mutationFn: (v: NewGoal) => postData<Goal>(GOALS_PATH, v), onSuccess: refresh });
  const remove = useMutation({ mutationFn: (id: string) => deleteData<unknown>(goalPath(id)), onSuccess: refresh });
  const error = create.error ?? remove.error;
  return (
    <section aria-label="Goals" className="space-y-3 rounded-xl border border-line bg-surface p-4">
      <h3 className="text-sm font-semibold text-ink">Goals</h3>
      {goals.isPending ? <Skeleton lines={2} /> : goals.error ? (
        <p role="alert" className="text-sm text-negative">
          {errorMessage(goals.error, "Couldn't load your goals")}{" "}
          <button type="button" onClick={() => goals.refetch()} className="font-medium underline">Try again</button>
        </p>
      ) : (
        <>
          {goals.data.length === 0 && <p className="text-sm text-ink-muted">No goals yet: cut a habit by a percentage, or keep it under an amount.</p>}
          <ul className="space-y-3">{goals.data.map((g) => <li key={g.id}><GoalCard goal={g} busy={remove.isPending} onDelete={() => remove.mutate(g.id)} /></li>)}</ul>
          <AddGoal busy={create.isPending} onAdd={(v) => create.mutate(v)} />
        </>
      )}
      {error && <p role="alert" className="text-sm text-negative">{errorMessage(error, "Couldn't save the goal")}</p>}
    </section>
  );
}

function AddGoal({ busy, onAdd }: { busy: boolean; onAdd: (v: NewGoal) => void }) {
  const lenses = useQuery({ queryKey: ["coach", "lenses"], queryFn: () => getData<LensesData>(LENSES_PATH) });
  const [lens, setLens] = useState("");
  const [kind, setKind] = useState<GoalKind>("cut_percent");
  const [value, setValue] = useState("");
  const [period, setPeriod] = useState<"weekly" | "monthly">("weekly");
  const ok = lens !== "" && validGoalValue(kind, value);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!ok) return;
    onAdd({ lens, kind, value: value.trim(), period });
    setValue("");
  };
  return (
    <form onSubmit={submit} className="grid gap-2 text-sm sm:grid-cols-5">
      <select aria-label="Goal habit" value={lens} onChange={(e) => setLens(e.target.value)} className="h-10 rounded-lg border border-line bg-surface px-2">
        <option value="">Habit…</option>
        {(lenses.data?.lenses ?? []).map((l) => <option key={l.id} value={l.id}>{l.label}</option>)}
      </select>
      <select aria-label="Goal type" value={kind} onChange={(e) => setKind(e.target.value as GoalKind)} className="h-10 rounded-lg border border-line bg-surface px-2">
        <option value="cut_percent">Cut by %</option>
        <option value="cap_amount">Keep under ₹</option>
      </select>
      <input aria-label={kind === "cut_percent" ? "Cut by percent" : "Keep under rupees"} inputMode="decimal" value={value} onChange={(e) => setValue(e.target.value)}
        placeholder={kind === "cut_percent" ? "30" : "4000"} className="h-10 rounded-lg border border-line px-2" />
      <select aria-label="Goal period" value={period} onChange={(e) => setPeriod(e.target.value as "weekly" | "monthly")} className="h-10 rounded-lg border border-line bg-surface px-2">
        <option value="weekly">Each week</option>
        <option value="monthly">Each month</option>
      </select>
      <button type="submit" disabled={busy || !ok} className="h-10 rounded-lg bg-accent px-3 font-semibold text-white disabled:opacity-40">Set goal</button>
    </form>
  );
}
