import type { Page } from "@playwright/test";
import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { MockApi } from "./support/mock-api";

/** Review → "Same merchant?" in a real browser (AOS-709): one tap, Undo for 5 s, then sent; plus attack checks (D-100). */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));

function withProposals(api: MockApi, list = [fx.mergeProposal(), fx.mergeProposal({ id: "mp-2", name: "Kuku FM", names: [{ name: "Kuku FM", payments: 3 }, { name: "Kukufm", payments: 1 }] })]) {
  api.on("GET /api/v1/merchants/merge-proposals", () => ({ data: list }))
    .on("POST /api/v1/merchants/merge-proposals/:id/:action", (_r, u) => {
      const id = u.pathname.split("/")[5];
      list.splice(list.findIndex((p) => p.id === id), 1);
      return { data: { id, merged: u.pathname.endsWith("/confirm"), name: "x" } };
    });
}

test.describe("Review: same merchant?", () => {
  test("Undo means nothing is sent; otherwise the merge is sent after 5 s and stays gone", async ({ signedIn: page, api }) => {
    withProposals(api);
    await page.goto("/review");
    const card = page.getByRole("listitem", { name: "Same merchant: Netflix?" });
    await expect(card.getByText("NETFLIX.COM")).toBeVisible();
    await card.getByRole("button", { name: "Same merchant", exact: true }).click();
    await expect(card).toBeHidden();
    await page.getByRole("status").getByRole("button", { name: "Undo" }).click();
    await expect(card).toBeVisible();
    await page.waitForTimeout(5500);
    expect(api.calls.filter((c) => c.method === "POST")).toEqual([]);

    await card.getByRole("button", { name: "Same merchant", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("Merging into Netflix");
    await expect.poll(() => api.callsTo("POST", "/api/v1/merchants/merge-proposals/mp-1/confirm").length, { timeout: 8000 }).toBe(1);
    await page.reload();
    await expect(page.getByRole("listitem", { name: "Same merchant: Netflix?" })).toHaveCount(0);
    await expect(page.getByRole("listitem", { name: "Same merchant: Kuku FM?" })).toBeVisible();
  });

  test("Different is sent when you leave the page", async ({ signedIn: page, api }) => {
    withProposals(api);
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Same merchant: Kuku FM?" }).getByRole("button", { name: "Different", exact: true }).click();
    await page.getByRole("navigation", { name: "Main navigation" }).locator("visible=true").getByRole("link", { name: "Home" }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect.poll(() => api.callsTo("POST", "/api/v1/merchants/merge-proposals/mp-2/reject").length).toBe(1);
  });

  test("a refused answer is reported and the card comes back", async ({ signedIn: page, api }) => {
    withProposals(api);
    api.on("POST /api/v1/merchants/merge-proposals/:id/:action", () => ({ status: 404, error: { code: "MERCHANT_MERGE_001", message: "Proposal not found or already decided" } }));
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Same merchant: Netflix?" }).getByRole("button", { name: "Same merchant", exact: true }).click();
    await expect(alertOf(page)).toContainText("Netflix: Proposal not found or already decided", { timeout: 8000 });
    await expect(page.getByRole("listitem", { name: "Same merchant: Netflix?" })).toBeVisible();
  });

  test("with nothing to ask the section isn't shown, and it is accessible when it is", async ({ signedIn: page, api }) => {
    await page.goto("/review");
    await expect(page.getByText("All clear — your totals are final.")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Same merchant?" })).toHaveCount(0);
    withProposals(api);
    await page.reload();
    await expect(page.getByRole("heading", { name: "Same merchant?" })).toBeVisible();
    await expectAccessible(page);
  });

  // ── Attack checks (D-100) ──

  test("signed out, Review goes to the login page and asks the API nothing", async ({ page, api }) => {
    withProposals(api);
    await page.goto("/review");
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("GET", "/api/v1/merchants/merge-proposals")).toHaveLength(0);
  });

  test("a forged or expired session is refused and ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/merchants/merge-proposals", () => ({ status: 401, error: { code: "AUTH_001", message: "Invalid token" } }))
      .on("GET /api/v1/transactions/suggestions", () => ({ status: 401, error: { code: "AUTH_001", message: "Invalid token" } }))
      .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/review");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("hostile merchant names render as text, never as markup, and no token reaches the page", async ({ signedIn: page, api }) => {
    const evil = '<img src=x onerror="window.__pwned=1"><script>window.__pwned=2</script>';
    withProposals(api, [fx.mergeProposal({ name: evil, names: [{ name: evil, payments: 1 }, { name: `${evil} Pvt Ltd`, payments: 1 }] })]);
    await page.goto("/review");
    await expect(page.getByText(`Are these all ${evil}?`)).toBeVisible();
    await expect(page.getByRole("main").locator("img[src='x']")).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
    const html = await page.content();
    expect(html).not.toContain("e2e-session");
    // (The test session cookie is set by the fixture without HttpOnly, so document.cookie isn't checked here; the real one is HttpOnly.)
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }))).not.toContain("e2e-session");
  });
});
