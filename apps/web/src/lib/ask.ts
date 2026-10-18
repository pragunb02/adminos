/**
 * "Ask your money" (AOS-711, story AOS-S7.3): the shapes `POST /api/v1/ask` returns and the helpers the Ask
 * page uses. Every number on the page comes from the API (D-67); this file only formats and links.
 */

import { categoryLabel } from "./analytics";
import { formatInr } from "./money";

export const ASK_PATH = "/api/v1/ask";
export const ASK_FEEDBACK_PATH = "/api/v1/ask/feedback";
export const MAX_QUESTION = 300;

export type AskTool = "spending_summary" | "category_spend" | "merchant_spend" | "top_merchants" | "spending_trend";
export type AskSource = "model" | "template" | "declined" | "unanswered";

export interface AskLine {
  label: string;
  spent: string;
  payments: number;
  category: string | null;
}

export interface AskResult {
  spent: string | null;
  received: string | null;
  payments: number | null;
  total: string | null;
  rows: AskLine[];
  points: { start: string; spent: string }[];
}

export interface AskStep {
  id: string;
  tool: AskTool;
  period: { from: string; to: string } | null;
  category: string | null;
  merchant: string | null;
  bucket: "day" | "week" | "month" | null;
  limit: number | null;
  result: AskResult | null;
  /** Why the step didn't run (e.g. `future_period`), when it didn't. */
  skipped: string | null;
}

export interface AskAnswer {
  askId: string;
  answer: string;
  source: AskSource;
  notice: string | null;
  declineReason: string | null;
  steps: AskStep[];
  comparisons: { later: string; earlier: string; difference: string; changePct: string | null }[];
}

export interface AskStatus {
  enabled: boolean;
  maxQuestionLength: number;
}

/** Tappable starting points, in English and Hinglish, each answerable by the tools. */
export const SUGGESTIONS = [
  "How much on Zepto last month?",
  "Food vs last month",
  "Biggest spend this week",
  "Kitna kharcha hua aaj?",
  "Top 5 merchants this month",
  "Swiggy pe is hafte kitna gaya?",
] as const;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "18 Oct 2026" from "2026-10-18". */
export function shortDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return `${d} ${MONTHS[m - 1]} ${y}`;
}

/** "1 Sep – 30 Sep 2026", "17 Oct 2026", or "28 Dec 2025 – 3 Jan 2026". */
export function periodLabel(period: { from: string; to: string }): string {
  if (period.from === period.to) return shortDate(period.from);
  const [fy] = period.from.split("-");
  const [ty] = period.to.split("-");
  const from = shortDate(period.from);
  return `${fy === ty ? from.slice(0, -5) : from} – ${shortDate(period.to)}`;
}

/** What a step looked up, in plain words. */
export function stepTitle(step: AskStep): string {
  const cat = step.category ? categoryLabel(step.category) : null;
  switch (step.tool) {
    case "spending_summary":
      return "Total spending";
    case "category_spend":
      return cat ? `Spending on ${cat}` : "Spending by category";
    case "merchant_spend":
      return `Spending at ${step.merchant ?? "a merchant"}`;
    case "top_merchants":
      return `Top ${step.limit ?? 5} merchants${cat ? ` in ${cat}` : ""}`;
    case "spending_trend":
      return `Spending by ${step.bucket ?? "day"}${cat ? ` on ${cat}` : ""}`;
  }
}

const SKIPPED: Record<string, string> = {
  future_period: "That period hasn't started yet.",
  period_too_long: "That period is longer than 3 years.",
  too_many_points: "Too many points to show; try a bigger bucket.",
};

export const skippedText = (code: string) => SKIPPED[code] ?? "This step couldn't run.";

/**
 * The console screen that shows the same payments: Activity with the step's dates, its category and the
 * merchant as the search. Built with URLSearchParams, so a merchant name can't add or change parameters.
 */
export function activityHref(step: AskStep): string | null {
  if (!step.period) return null;
  const q = new URLSearchParams({ from: step.period.from, to: step.period.to });
  if (step.category) q.set("category", step.category);
  if (step.merchant) q.set("q", step.merchant);
  return `/transactions?${q}`;
}

/** One line per number a step returned, for the "How I got this" table. */
export function resultLines(step: AskStep): { label: string; value: string }[] {
  const r = step.result;
  if (!r) return [];
  if (step.tool === "spending_summary") {
    return [
      { label: "Spent", value: formatInr(r.spent ?? "0") },
      { label: "Received", value: formatInr(r.received ?? "0") },
      { label: "Payments", value: String(r.payments ?? 0) },
    ];
  }
  const lines = r.points.length
    ? r.points.map((p) => ({ label: step.bucket === "month" ? shortDate(p.start).slice(-8) : `${step.bucket === "week" ? "Week of " : ""}${shortDate(p.start)}`, value: formatInr(p.spent) }))
    : r.rows.map((row) => ({ label: step.tool === "category_spend" ? categoryLabel(row.label) : row.label, value: `${formatInr(row.spent)} · ${row.payments} ${row.payments === 1 ? "payment" : "payments"}` }));
  if (!lines.length) lines.push({ label: "No spending", value: formatInr("0") });
  if (r.total !== null && step.tool !== "top_merchants") lines.push({ label: "Total", value: formatInr(r.total) });
  return lines;
}

/** Calm, specific text for each error the Ask API returns. */
export function askErrorText(code: string, message: string): string {
  switch (code) {
    case "ASK_004":
      return "Ask isn't turned on for this server yet. It's off until AI_ASK_ENABLED is set (see .env.example).";
    case "RATE_001":
      return message;
    case "NETWORK":
      return "Couldn't reach AdminOS. Check your connection and try again.";
    default:
      return message || "Something went wrong. Try again.";
  }
}
