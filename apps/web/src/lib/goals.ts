/** Goals (AOS-907): API types and helpers. Money is an exact decimal string (D-30). */
import { formatInr } from "./money";

export type GoalKind = "cut_percent" | "cap_amount";
export interface GoalPeriod { from: string; to: string; spent: string; target: string; met: boolean; current: boolean }
export interface Goal {
  id: string; lens: string; label: string; kind: GoalKind; value: string; period: "weekly" | "monthly";
  baseline: string | null; target: string; startedOn: string;
  periods: GoalPeriod[]; metCount: number; completedCount: number; streak: number; bestStreak: number;
}
export interface NewGoal { lens: string; kind: GoalKind; value: string; period: "weekly" | "monthly" }

export const GOALS_PATH = "/api/v1/coach/goals";
export const goalPath = (id: string) => `${GOALS_PATH}/${encodeURIComponent(id)}`;

/** "Cut Quick commerce by 30% (to ₹479 a week)" / "Keep Travel under ₹5,000 a month". */
export function goalTitle(g: Goal): string {
  const per = g.period === "weekly" ? "a week" : "a month";
  return g.kind === "cut_percent"
    ? `Cut ${g.label} by ${Number(g.value)}% (to ${formatInr(g.target)} ${per})`
    : `Keep ${g.label} under ${formatInr(g.target)} ${per}`;
}

/** "3-day no-spend streak (best 7)". */
export const streakText = (g: Goal) => `${g.streak}-day no-spend streak${g.bestStreak > g.streak ? ` (best ${g.bestStreak})` : ""}`;

/** "This week: ₹300 of ₹479" and whether it's on track so far. */
export function currentText(g: Goal): string {
  const c = g.periods.at(-1);
  if (!c) return "";
  return `${g.period === "weekly" ? "This week" : "This month"}: ${formatInr(c.spent)} of ${formatInr(c.target)}${c.met ? "" : " (over)"}`;
}

export const validGoalValue = (kind: GoalKind, v: string) =>
  kind === "cut_percent" ? /^\d{1,2}$/.test(v.trim()) && Number(v) >= 1 && Number(v) <= 90 : /^\d{1,8}(\.\d{1,2})?$/.test(v.trim()) && Number(v) > 0;
