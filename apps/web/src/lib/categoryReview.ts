/**
 * "Check these categories" on Review (AOS-708): the learned categoriser's and the AI's unsure guesses, ordered by the
 * API by uncertainty × amount. Answering is a remembered correction (D-41) the model learns from (AOS-707), and may keep
 * a scrubbed copy in the user's local golden set. Components use these helpers; they never build the URLs.
 */

import { categoryLabel } from "./analytics";
import { CATEGORIES } from "./consolePeriod";
import { getData, postData } from "./query";
import type { CategoryAnswer, CategoryGuess, CategoryModelStatus, CategoryQuestion, GoldenRow } from "./types";

export const QUESTIONS_KEY = ["categoryReview", "questions"] as const;
export const MODEL_KEY = ["categoryReview", "model"] as const;
export const GOLDEN_KEY = ["categoryReview", "golden"] as const;

/** Where the local golden set downloads from (a same-origin link; the session cookie goes with it). */
export const GOLDEN_CSV_URL = "/api/v1/categories/golden.csv";

export const getQuestions = () => getData<CategoryQuestion[]>("/api/v1/categories/review?limit=20");
export const getModelStatus = () => getData<CategoryModelStatus>("/api/v1/categories/model");
export const getGoldenRows = () => getData<GoldenRow[]>("/api/v1/categories/golden");

export const answerQuestion = (transactionId: string, category: string, addToGolden: boolean) =>
  postData<CategoryAnswer>(`/api/v1/categories/review/${encodeURIComponent(transactionId)}`, { category, addToGolden });

/** At most this many one-tap corrections beside "Yes": the model's next guesses, then Other. */
const QUICK = 3;

/** The one-tap corrections for a guess: its alternatives, then Other; never the guess itself or Transfer. */
export function quickCorrections(guess: CategoryGuess): string[] {
  const picks = [...guess.alternatives, "other"].filter((c) => c !== guess.category && c !== "transfer" && (CATEGORIES as readonly string[]).includes(c));
  return [...new Set(picks)].slice(0, QUICK);
}

/** Every other category, for "Something else…". Transfers come from your accounts, not from a guess (D-41). */
export const otherCategories = (guess: CategoryGuess, quick: string[]) =>
  CATEGORIES.filter((c) => c !== "transfer" && c !== guess.category && !quick.includes(c));

/** "Looks like Health (55% sure) · because of ‘medicos’" or "Set to Health for you (82% sure)". */
export function guessLine(guess: CategoryGuess): string {
  const what = guess.applied ? `Set to ${categoryLabel(guess.category)} for you` : `Looks like ${categoryLabel(guess.category)}`;
  const why = guess.cues.length > 0 ? ` · because of ‘${guess.cues[0]}’` : guess.reason ? ` · ${guess.reason}` : "";
  return `${what} (${Math.round(guess.confidence * 100)}% sure, ${guess.by === "ai" ? "AI" : "learned from your answers"})${why}`;
}

/** What the Undo bar says while the answer waits to be sent. */
export const answeredMessage = (q: CategoryQuestion, category: string) =>
  `${category === q.guess.category ? "Kept" : "Changed to"} ${categoryLabel(category)} for ${q.transaction.merchantName || "this payee"}; remembered`;

/** What happened to the scrubbed copy, in words; null when no copy was asked for. */
export function goldenNote(answer: CategoryAnswer): string | null {
  const g = answer.golden;
  if (!g) return null;
  if (g.outcome === "added") return "Kept a scrubbed copy in your local test set.";
  if (g.outcome === "already_there") return "Your local test set already has this one.";
  const why: Record<string, string> = {
    looks_like_a_person: "it looks like a payment to a person",
    no_name: "it has no clear shop name",
    not_spending: "only spending and refunds are kept",
  };
  return `Not added to your local test set: ${why[g.reason ?? ""] ?? "it can't be scrubbed automatically"}.`;
}

/** One line on what the learned categoriser is doing, or null when learning is off. */
export function learningLine(s: CategoryModelStatus): string | null {
  if (!s.enabled) return null;
  switch (s.state) {
    case "cold_start":
      return `It starts learning from your answers after ${s.correctionsNeeded} more ${s.correctionsNeeded === 1 ? "correction" : "corrections"}.`;
    case "ready":
      return s.mode === "apply" ? "It learns from every answer, overnight." : "It learns from every answer; for now it only suggests.";
    case "corrupt":
      return "Learning is paused until tonight's retraining.";
    default:
      return null;
  }
}
