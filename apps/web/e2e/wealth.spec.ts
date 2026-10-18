import type { Page } from "@playwright/test";
import { wx } from "../test/wealthFixtures";
import { expect, expectAccessible, isMobile, test } from "./support/fixtures";

// Statement passwords are made per run, so no password-like literal sits in the source.
const statementSecret = () => `cas-${crypto.randomUUID()}`;

const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));

/** Wealth in a real browser (AOS-820): More → Wealth, holdings with their working, CAS import and undo, Review. */
test.describe("Wealth", () => {
  test("is under More and shows the total, the gain in words and How we got this", async ({ signedIn: page }) => {
    await page.goto("/more");
    await page.getByRole("main").getByRole("link", { name: "Wealth", exact: true }).click();
    await expect(page).toHaveURL(/\/wealth$/);
    const total = page.getByRole("region", { name: "Your mutual funds" });
    await expect(total.getByText("Up ₹3,000 (+20.0%)", { exact: true })).toBeVisible();
    await expect(total.getByText("+17.5% so far (under a year)", { exact: true })).toBeVisible();
    const h = page.getByRole("article", { name: "Example Flexi Cap Fund - Direct Plan - Growth" });
    await h.getByText("How we got this", { exact: true }).click();
    await expect(h.getByText("Value = units × price = 18000.00", { exact: true })).toBeVisible();
    await expectAccessible(page);
  });

  test("a holding that doesn't add up shows on Wealth and in Review, outside the totals", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/wealth/holdings", () => ({ data: wx.overview({ needsReview: [wx.blocked()] }) }))
       .on("GET /api/v1/wealth/reconciliation", () => ({ data: { holdings: [wx.blocked()], blocked: 1 } }));
    await page.goto("/wealth");
    await expect(page.getByRole("region", { name: "Needs review (1)" })).toBeVisible();
    await page.goto("/review");
    const block = page.getByRole("region", { name: "Investments that don't add up" });
    await expect(block.getByText("Units don't add up: the statement says 11 units, the transactions give 10", { exact: true })).toBeVisible();
    await expectAccessible(page);
  });

  test("a CAS imports, says what needs review, and an import is undone", async ({ signedIn: page, api }) => {
    await page.goto("/wealth/import");
    await page.getByLabel("Statement PDF").setInputFiles({ name: "cas.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4 synthetic") });
    await page.getByLabel("Statement password").fill(statementSecret());
    await page.getByRole("button", { name: "Import", exact: true }).click();
    await expect(page.getByRole("status", { name: "Import result" })).toContainText("8 new transactions · 1 holding add up · 1 needs review");
    await expect.poll(() => api.callsTo("POST", "/api/v1/wealth/imports/cas").length).toBe(1);
    await page.getByRole("button", { name: "Undo the import of 31 Dec 2025" }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/wealth/imports/b-1/undo").length).toBe(1);
    await expectAccessible(page);
  });

  test("a wrong password is explained", async ({ signedIn: page, api }) => {
    api.on("POST /api/v1/wealth/imports/cas", () => ({ status: 422, error: { code: "PASSWORD_INCORRECT", message: "That password doesn't open this statement" } }));
    await page.goto("/wealth/import");
    await page.getByLabel("Statement PDF").setInputFiles({ name: "cas.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4") });
    await page.getByLabel("Statement password").fill(statementSecret());
    await page.getByRole("button", { name: "Import", exact: true }).click();
    await expect(alertOf(page)).toContainText("That password doesn't open this statement. Try again.");
  });

  test("the desktop rail lists Wealth", async ({ signedIn: page }) => {
    test.skip(isMobile(page), "phones reach Wealth through More");
    await page.goto("/");
    await expect(page.getByRole("link", { name: "Wealth", exact: true }).first()).toBeVisible();
  });
});

test.describe("Wealth: attacks (D-100)", () => {
  test("signed out, both Wealth pages go to login", async ({ page }) => {
    for (const path of ["/wealth", "/wealth/import"]) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/login$/);
    }
  });

  test("a session the API refuses ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/wealth/holdings", () => ({ status: 401, error: { code: "AUTH_002", message: "Token invalid" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/wealth");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("scheme names, fund houses and reasons from statements render as text", async ({ signedIn: page, api }) => {
    const hostile = `<img src=x onerror="window.__pwned=1">`;
    api.on("GET /api/v1/wealth/holdings", () => ({ data: wx.overview({
      groups: [{ assetClass: "other", value: "1.00", invested: "1.00", holdings: [wx.holding({ scheme: hostile, amc: hostile, issues: [hostile] })] }],
      needsReview: [wx.blocked({ scheme: hostile, reason: hostile })],
    }) }));
    await page.goto("/wealth");
    await expect(page.getByRole("article", { name: hostile })).toBeVisible();
    await expect.poll(() => page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
    expect(await page.locator("main img").count()).toBe(0);
  });

  test("the statement password is never stored in the page or the URL", async ({ signedIn: page }) => {
    await page.goto("/wealth/import");
    await page.getByLabel("Statement PDF").setInputFiles({ name: "cas.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4") });
    const secret = statementSecret();
    await page.getByLabel("Statement password").fill(secret);
    await page.getByRole("button", { name: "Import", exact: true }).click();
    await expect(page.getByRole("status", { name: "Import result" })).toBeVisible();
    await expect(page.getByLabel("Statement password")).toHaveValue("");
    expect(page.url()).not.toContain(secret);
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }) + document.cookie);
    expect(stored).not.toContain(secret);
  });
});
