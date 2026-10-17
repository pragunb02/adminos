import type { Page } from "@playwright/test";
import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { MockApi } from "./support/mock-api";

/** Review → messages read by AI (AOS-705) in a real browser: decide each, untrusted text stays text. */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));

function withProposals(api: MockApi, list = [fx.messageProposal(), fx.messageProposal({ id: "mp-2", direction: "credit", amount: "5000.00", merchant: "RAHUL K", channel: "whatsapp" })]) {
  api.on("GET /api/v1/review/messages", () => ({ data: list }))
    .on("POST /api/v1/review/messages/:id/:action", (_r, u) => {
      const [, , , , , id, action] = u.pathname.split("/");
      const i = list.findIndex((p) => p.id === id);
      if (i < 0) return { status: 404, error: { code: "REVIEW_001", message: "Not found or already decided" } };
      list.splice(i, 1);
      return { data: { id, status: action === "confirm" ? "confirmed" : "dismissed", duplicate: false } };
    });
}

test.describe("Review: messages read by AI", () => {
  test("adding one confirms it and it leaves the list, also after a reload", async ({ signedIn: page, api }) => {
    withProposals(api);
    await page.goto("/review");
    const out = page.getByRole("listitem", { name: "Money out read by AI: ZEPTO", exact: true });
    await expect(out).toContainText("−₹249.00");
    await out.getByRole("button", { name: "Add to my money", exact: true }).click();
    await expect(page.getByRole("status").filter({ hasText: "Added to your money" })).toBeVisible();
    await expect.poll(() => api.callsTo("POST", "/api/v1/review/messages/mp-1/confirm").length).toBe(1);
    await expect(out).toBeHidden();
    await page.reload();
    await expect(page.getByRole("listitem", { name: "Money in read by AI: RAHUL K", exact: true })).toBeVisible();
    await expect(page.getByRole("listitem", { name: "Money out read by AI: ZEPTO", exact: true })).toHaveCount(0);
  });

  test("not a payment dismisses it", async ({ signedIn: page, api }) => {
    withProposals(api);
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Money in read by AI: RAHUL K", exact: true }).getByRole("button", { name: "Not a payment", exact: true }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/review/messages/mp-2/dismiss").length).toBe(1);
    await expect(page.getByRole("status").filter({ hasText: "Left out of your totals" })).toBeVisible();
  });

  test("a refused decision is reported and the payment stays", async ({ signedIn: page, api }) => {
    withProposals(api);
    api.on("POST /api/v1/review/messages/:id/:action", () => ({ status: 404, error: { code: "REVIEW_001", message: "Not found or already decided" } }));
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Money out read by AI: ZEPTO", exact: true }).getByRole("button", { name: "Add to my money", exact: true }).click();
    await expect(alertOf(page)).toContainText("Not found or already decided");
    await expect(page.getByRole("listitem", { name: "Money out read by AI: ZEPTO", exact: true })).toBeVisible();
  });

  test("a merchant name from a message renders as text, never as markup", async ({ signedIn: page, api }) => {
    const hostile = "<img src=x onerror=\"window.__pwned=1\">";
    withProposals(api, [fx.messageProposal({ merchant: hostile })]);
    await page.goto("/review");
    await expect(page.getByText(hostile, { exact: true })).toBeVisible();
    await expect(page.locator("main img[src='x']")).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
  });

  test("nothing waiting shows no section", async ({ signedIn: page }) => {
    await page.goto("/review");
    await expect(page.getByText("All clear — your totals are final.")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Messages read by AI", exact: true })).toHaveCount(0);
  });

  test("signed out, Review goes to the login page and nothing is asked", async ({ page, api }) => {
    withProposals(api);
    await page.goto("/review");
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("GET", "/api/v1/review/messages")).toEqual([]);
  });

  test("with payments waiting it is accessible", async ({ signedIn: page, api }) => {
    withProposals(api);
    await page.goto("/review");
    await expect(page.getByRole("heading", { name: "Messages read by AI", exact: true })).toBeVisible();
    await expectAccessible(page);
  });
});

/** Review → formats AdminOS can learn (AOS-706): approve or reject a learnt format; its wording renders as text. */
function withTemplates(api: MockApi, list = [fx.messageTemplate(), fx.messageTemplate({ id: "mt-2", direction: "credit", seen: 4, shape: "Rs <AMT> Cr. to A/c XX<ACCT> from <M>" })]) {
  api.on("GET /api/v1/review/templates", () => ({ data: list }))
    .on("POST /api/v1/review/templates/:id/:action", (_r, u) => {
      const [, , , , , id, action] = u.pathname.split("/");
      const i = list.findIndex((t) => t.id === id);
      if (i < 0) return { status: 404, error: { code: "REVIEW_002", message: "Not found or already decided" } };
      list.splice(i, 1);
      return { data: { id, status: action === "approve" ? "approved" : "rejected" } };
    });
}

test.describe("Review: formats AdminOS can learn", () => {
  test("approving a format sends it once and it leaves the list", async ({ signedIn: page, api }) => {
    withTemplates(api);
    await page.goto("/review");
    const out = page.getByRole("listitem", { name: "Money out format seen 3 times", exact: true });
    await expect(out).toContainText("Card XX[last 4] charged Rs.[amount] at [payee] on [date].");
    await out.getByRole("button", { name: "Read it myself", exact: true }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/review/templates/mt-1/approve").length).toBe(1);
    await expect(page.getByRole("status").filter({ hasText: "AdminOS will read this format itself" })).toBeVisible();
    await expect(out).toBeHidden();
  });

  test("keep reviewing rejects it, and a refused decision is reported", async ({ signedIn: page, api }) => {
    withTemplates(api);
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Money in format seen 4 times", exact: true }).getByRole("button", { name: "Keep reviewing", exact: true }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/review/templates/mt-2/reject").length).toBe(1);
    api.on("POST /api/v1/review/templates/:id/:action", () => ({ status: 404, error: { code: "REVIEW_002", message: "Not found or already decided" } }));
    await page.getByRole("listitem", { name: "Money out format seen 3 times", exact: true }).getByRole("button", { name: "Read it myself", exact: true }).click();
    await expect(alertOf(page)).toContainText("Not found or already decided");
  });

  test("a format's wording renders as text, never as markup", async ({ signedIn: page, api }) => {
    withTemplates(api, [fx.messageTemplate({ shape: "<img src=x onerror=\"window.__pwned=1\"> Rs <AMT>" })]);
    await page.goto("/review");
    await expect(page.getByRole("listitem", { name: "Money out format seen 3 times", exact: true })).toContainText("<img src=x");
    await expect(page.locator("main img[src='x']")).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
  });

  test("with formats waiting it is accessible", async ({ signedIn: page, api }) => {
    withTemplates(api);
    await page.goto("/review");
    await expect(page.getByRole("heading", { name: "Formats AdminOS can learn", exact: true })).toBeVisible();
    await expectAccessible(page);
  });
});
