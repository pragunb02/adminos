import { http } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { ok } from "../../test/msw/envelope";
import { fx } from "../../test/fixtures";
import {
  answerQuestion, answeredMessage, getGoldenRows, getModelStatus, getQuestions, goldenNote, guessLine, learningLine, otherCategories,
  quickCorrections,
} from "./categoryReview";
import type { CategoryAnswer, CategoryModelStatus } from "./types";

const guess = (over = {}) => fx.categoryQuestion({}, over).guess;
const answer = (golden: CategoryAnswer["golden"]): CategoryAnswer => ({ transaction: fx.transaction(), remembered: true, relabelled: 0, golden });
const status = (over: Partial<CategoryModelStatus> = {}): CategoryModelStatus => ({ enabled: true, state: "ready", mode: "apply", corrections: 30, correctionsNeeded: 0, ...over });

describe("category review helpers (AOS-708)", () => {
  it("offers the model's next guesses and Other as one-tap corrections, never the guess or Transfer", () => {
    expect(quickCorrections(guess())).toEqual(["shopping", "other"]);
    expect(quickCorrections(guess({ alternatives: ["health", "transfer", "food", "rent", "food", "education"] }))).toEqual(["food", "education", "other"]);
    expect(quickCorrections(guess({ category: "food", alternatives: [] }))).toEqual(["other"]);
  });

  it("lists every other category for Something else", () => {
    const g = guess();
    const rest = otherCategories(g, quickCorrections(g));
    expect(rest).not.toContain("health");
    expect(rest).not.toContain("shopping");
    expect(rest).not.toContain("other");
    expect(rest).not.toContain("transfer");
    expect(rest).toContain("food");
  });

  it("says what the model thinks and why", () => {
    expect(guessLine(guess())).toBe("Looks like Health (55% sure, learned from your answers) · because of ‘medicos’");
    expect(guessLine(guess({ by: "ai", cues: [], reason: "Bakery chain", category: "food", confidence: 0.6 }))).toBe("Looks like Food (60% sure, AI) · Bakery chain");
    expect(guessLine(guess({ applied: true, cues: [], reason: null, category: "emi", confidence: 0.86 }))).toBe("Set to EMI for you (86% sure, learned from your answers)");
  });

  it("the Undo bar says kept or changed, and remembered", () => {
    const q = fx.categoryQuestion();
    expect(answeredMessage(q, "health")).toBe("Kept Health for Sai Medicos; remembered");
    expect(answeredMessage(q, "shopping")).toBe("Changed to Shopping for Sai Medicos; remembered");
    expect(answeredMessage(fx.categoryQuestion({ transaction: fx.transaction({ merchantName: null }) }), "food")).toBe("Changed to Food for this payee; remembered");
  });

  it("explains what happened to the scrubbed copy", () => {
    expect(goldenNote(answer(null))).toBeNull();
    expect(goldenNote(answer(undefined))).toBeNull();
    expect(goldenNote(answer({ outcome: "added" }))).toBe("Kept a scrubbed copy in your local test set.");
    expect(goldenNote(answer({ outcome: "already_there" }))).toBe("Your local test set already has this one.");
    expect(goldenNote(answer({ outcome: "refused", reason: "looks_like_a_person" }))).toBe("Not added to your local test set: it looks like a payment to a person.");
    expect(goldenNote(answer({ outcome: "refused", reason: "no_name" }))).toContain("no clear shop name");
    expect(goldenNote(answer({ outcome: "refused", reason: "not_spending" }))).toContain("only spending and refunds");
    expect(goldenNote(answer({ outcome: "refused", reason: "new_reason" }))).toContain("can't be scrubbed automatically");
    expect(goldenNote(answer({ outcome: "refused" }))).toContain("can't be scrubbed automatically");
  });

  it("describes the learned categoriser, and says nothing when learning is off", () => {
    expect(learningLine(status({ enabled: false, state: "off" }))).toBeNull();
    expect(learningLine(status({ state: "off" }))).toBeNull();
    expect(learningLine(status({ state: "cold_start", correctionsNeeded: 12 }))).toBe("It starts learning from your answers after 12 more corrections.");
    expect(learningLine(status({ state: "cold_start", correctionsNeeded: 1 }))).toContain("1 more correction.");
    expect(learningLine(status())).toBe("It learns from every answer, overnight.");
    expect(learningLine(status({ mode: "suggest" }))).toContain("only suggests");
    expect(learningLine(status({ state: "corrupt" }))).toContain("paused");
  });

  it("talks to the category review endpoints", async () => {
    let body: unknown = null;
    server.use(
      http.get("/api/v1/categories/review", ({ request }) => ok(new URL(request.url).searchParams.get("limit") === "20" ? [fx.categoryQuestion()] : [])),
      http.post("/api/v1/categories/review/:id", async ({ request, params }) => { body = { id: params.id, ...(await request.json() as object) }; return ok(answer(null)); }),
    );
    expect(await getQuestions()).toHaveLength(1);
    expect((await getModelStatus()).state).toBe("off");
    expect(await getGoldenRows()).toEqual([]);
    await answerQuestion("cq-1", "health", true);
    expect(body).toEqual({ id: "cq-1", category: "health", addToGolden: true });
  });
});
