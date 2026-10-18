import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { Page } from "@playwright/test";
import type { MockApi } from "./support/mock-api";

/** Review in a real browser (AOS-309): tabs in the URL, a decision waits 5 s for Undo, then is sent. */
function withSuggestions(api: MockApi) {
  const list = [
    fx.suggestion(),
    fx.suggestion({ id: "sg-2", relation: "transfer", reason: "same amount, your two accounts" }),
    fx.suggestion({ id: "sg-3", relation: "refund", reason: "credit from the same merchant" }),
  ];
  api.on("GET /api/v1/transactions/suggestions", () => ({ data: list }))
    .on("POST /api/v1/transactions/suggestions/:id/:action", (_r, u) => {
      const id = u.pathname.split("/")[5];
      list.splice(list.findIndex((s) => s.id === id), 1);
      return { data: { id } };
    });
}

test.describe("Review", () => {
  test("tabs live in the URL; back/forward/refresh restore them", async ({ signedIn: page, api }) => {
    withSuggestions(api);
    await page.goto("/review");
    await expect(page.getByRole("main").getByRole("listitem")).toHaveCount(3);
    await page.getByRole("button", { name: /^Transfers/ }).click();
    await expect(page).toHaveURL(/\/review\?kind=transfer$/);
    await expect(page.getByRole("main").getByRole("listitem")).toHaveCount(1);
    await page.getByRole("button", { name: /^Refunds/ }).click();
    await expect(page).toHaveURL(/\?kind=refund$/);
    await page.goBack();
    await expect(page).toHaveURL(/\?kind=transfer$/);
    await page.reload();
    await expect(page.getByRole("listitem", { name: "Transfer between your accounts?" })).toBeVisible();
    await page.goBack();
    await expect(page).toHaveURL(/\/review$/);
    await page.goForward();
    await expect(page).toHaveURL(/\?kind=transfer$/);
  });

  test("Undo means nothing is sent; otherwise it is sent after 5 s and the card stays gone", async ({ signedIn: page, api }) => {
    withSuggestions(api);
    await page.goto("/review");
    const dup = page.getByRole("listitem", { name: "Possible duplicate" });
    await dup.getByRole("button", { name: "Same payment" }).click();
    await expect(dup).toBeHidden();
    await page.getByRole("status").getByRole("button", { name: "Undo" }).click();
    await expect(dup).toBeVisible();
    await page.waitForTimeout(5500);
    expect(api.calls.filter((c) => c.method === "POST")).toEqual([]);

    await page.getByRole("listitem", { name: "Transfer between your accounts?" }).getByRole("button", { name: "Yes, a transfer" }).click();
    await expect(page.getByRole("status")).toContainText("Marked as a transfer");
    await expect.poll(() => api.callsTo("POST", "/api/v1/transactions/suggestions/sg-2/confirm").length, { timeout: 8000 }).toBe(1);
    await expect(page.getByRole("button", { name: /^All/ })).toContainText("(2)");
    await page.reload();
    await expect(page.getByRole("main").getByRole("listitem")).toHaveCount(2);
  });

  test("leaving the page sends a pending decision", async ({ signedIn: page, api }) => {
    withSuggestions(api);
    await page.goto("/review");
    await page.getByRole("listitem", { name: "A refund of this purchase?" }).getByRole("button", { name: "No" }).click();
    await page.getByRole("navigation", { name: "Main navigation" }).locator("visible=true").getByRole("link", { name: "Home" }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect.poll(() => api.callsTo("POST", "/api/v1/transactions/suggestions/sg-3/reject").length).toBe(1);
  });

  test("all clear when nothing waits", async ({ signedIn: page }) => {
    await page.goto("/review");
    await expect(page.getByText("All clear — your totals are final.")).toBeVisible();
  });

  test("with cards and the Undo bar it is accessible", async ({ signedIn: page, api }) => {
    withSuggestions(api);
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Possible duplicate" }).getByRole("button", { name: "Different" }).click();
    await expect(page.getByRole("status")).toBeVisible();
    await expectAccessible(page);
  });
});

/** Review → Check these categories in a real browser (AOS-708): one tap, Undo for 5 s, then a remembered correction. */
function withQuestions(api: MockApi, golden: { outcome: string; reason?: string } | null = null) {
  const list = [
    fx.categoryQuestion(),
    fx.categoryQuestion({ transaction: fx.transaction({ id: "cq-2", merchantName: "Bakers Dozen", category: "other", amount: "180" }), priority: 72 },
      { by: "ai", category: "food", confidence: 0.6, cues: [], reason: "Bakery chain", alternatives: [] }),
  ];
  api.on("GET /api/v1/categories/review", () => ({ data: list }))
    .on("GET /api/v1/categories/model", () => ({ data: { enabled: true, state: "cold_start", corrections: 8, correctionsNeeded: 12 } }))
    .on("POST /api/v1/categories/review/:id", (r, u) => {
      const id = u.pathname.split("/")[5];
      list.splice(list.findIndex((q) => q.transaction.id === id), 1);
      const body = r.postDataJSON() as { addToGolden: boolean };
      return { data: { transaction: fx.transaction({ id }), remembered: true, relabelled: 0, golden: body.addToGolden ? golden ?? { outcome: "added" } : null } };
    });
}

/** Alerts on the page, not Next's route announcer (it is also role="alert"). */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));

test.describe("Review: check these categories", () => {
  test("one tap confirms; it is sent after the Undo window and stays answered after a reload", async ({ signedIn: page, api }) => {
    withQuestions(api);
    await page.goto("/review");
    const region = page.getByRole("region", { name: "Check these categories" });
    await expect(region.getByText(/after 12 more corrections/)).toBeVisible();
    const first = page.getByRole("listitem", { name: "Category for Sai Medicos" });
    await expect(first.getByText(/Looks like Health \(55% sure/)).toBeVisible();
    await first.getByRole("button", { name: "Yes, Health" }).click();
    await expect(first).toBeHidden();
    await expect(page.getByRole("status")).toContainText("Kept Health for Sai Medicos; remembered");
    await expect.poll(() => api.callsTo("POST", "/api/v1/categories/review/cq-1").length, { timeout: 8000 }).toBe(1);
    expect(api.callsTo("POST", "/api/v1/categories/review/cq-1")[0].body).toEqual({ category: "health", addToGolden: false });
    await page.reload();
    await expect(page.getByRole("listitem", { name: "Category for Bakers Dozen" })).toBeVisible();
    await expect(page.getByRole("listitem", { name: "Category for Sai Medicos" })).toHaveCount(0);
  });

  test("a one-tap correction can be undone, and then nothing is sent", async ({ signedIn: page, api }) => {
    withQuestions(api);
    await page.goto("/review");
    const card = page.getByRole("listitem", { name: "Category for Sai Medicos" });
    await card.getByRole("button", { name: "Shopping", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("Changed to Shopping");
    await page.getByRole("status").getByRole("button", { name: "Undo" }).click();
    await expect(card).toBeVisible();
    await page.waitForTimeout(5500);
    expect(api.calls.filter((c) => c.method === "POST")).toEqual([]);
  });

  test("Something else picks any category, from the keyboard too", async ({ signedIn: page, api }) => {
    withQuestions(api);
    await page.goto("/review");
    const select = page.getByRole("combobox", { name: "Another category for Bakers Dozen" });
    await select.focus();
    await select.selectOption({ label: "Entertainment" });
    await expect(page.getByRole("status")).toContainText("Changed to Entertainment for Bakers Dozen");
    await expect.poll(() => api.callsTo("POST", "/api/v1/categories/review/cq-2").length, { timeout: 8000 }).toBe(1);
    expect(api.callsTo("POST", "/api/v1/categories/review/cq-2")[0].body).toEqual({ category: "entertainment", addToGolden: false });
  });

  test("keeping a scrubbed copy is asked for explicitly, and a refusal is explained", async ({ signedIn: page, api }) => {
    withQuestions(api, { outcome: "refused", reason: "looks_like_a_person" });
    await page.goto("/review");
    await page.getByRole("checkbox", { name: /keep a scrubbed copy/i }).check();
    await page.getByRole("listitem", { name: "Category for Sai Medicos" }).getByRole("button", { name: "Other", exact: true }).click();
    await expect(page.getByText("Not added to your local test set: it looks like a payment to a person.")).toBeVisible({ timeout: 8000 });
    expect(api.callsTo("POST", "/api/v1/categories/review/cq-1")[0].body).toEqual({ category: "other", addToGolden: true });
    await expect(alertOf(page)).toHaveCount(0);
  });

  test("an answer the API refuses is reported and the question comes back", async ({ signedIn: page, api }) => {
    withQuestions(api);
    api.on("POST /api/v1/categories/review/:id", () => ({ status: 404, error: { code: "TXN_001", message: "Transaction not found" } }));
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Category for Sai Medicos" }).getByRole("button", { name: "Yes, Health" }).click();
    await expect(alertOf(page)).toContainText("Sai Medicos: Transaction not found", { timeout: 8000 });
    await expect(page.getByRole("listitem", { name: "Category for Sai Medicos" })).toBeVisible();
  });

  test("with questions and the Undo bar it is accessible", async ({ signedIn: page, api }) => {
    withQuestions(api);
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Category for Bakers Dozen" }).getByRole("button", { name: "Yes, Food" }).click();
    await expect(page.getByRole("status")).toBeVisible();
    await expectAccessible(page);
    await page.getByRole("status").getByRole("button", { name: "Undo" }).click();
  });
});
