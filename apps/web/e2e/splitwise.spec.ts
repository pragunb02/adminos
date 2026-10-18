import type { Page } from "@playwright/test";
import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { MockApi } from "./support/mock-api";

/**
 * Splitwise import and review in a real browser (AOS-911, D-111; security gate D-100): the file's header names
 * are offered as text, the upload carries the chosen name, Review actions are sent, untrusted text never becomes
 * HTML, and nothing works without a session.
 */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));

const HOSTILE = "<img src=x onerror=\"window.__pwned=1\">";
const exportCsv = Buffer.from(
  "\uFEFFDate,Description,Category,Cost,Currency,Dev P.,Asha R.,\"<b>Kabir</b>\"\r\n" +
  "2026-10-03,Dinner at Toit,Dining out,4000.00,INR,3000.00,-1500.00,-1500.00\r\n",
);

function withEntries(api: MockApi) {
  const list = [
    fx.splitEntry(),
    fx.splitEntry({ id: "sw-2", kind: "payment", effect: "settle_in", description: "Payment", counterparty: HOSTILE, cost: "1500.00", ownerShare: "0.00",
      transaction: fx.transaction({ id: "t-cr", type: "credit", merchantName: "UPI Credit", amount: "1500.00" }) }),
    fx.splitEntry({ id: "sw-5", state: "applied", effect: "owed_spend", description: HOSTILE, ownerShare: "300.00" }),
  ];
  api.on("GET /api/v1/splitwise/entries", () => ({ data: list }))
    .on("GET /api/v1/splitwise/summary", () => ({ data: { balances: [{ currency: "INR", net: "1200.00" }], counts: {} } }))
    .on("POST /api/v1/splitwise/entries/:id/:action", (_r, u) => {
      const id = u.pathname.split("/")[5];
      const i = list.findIndex((e) => e.id === id);
      if (i >= 0) list.splice(i, 1);
      return { data: { id } };
    });
}

test.describe("Splitwise", () => {
  test("import: pick yourself from the file, upload once, see what changed", async ({ signedIn: page, api }) => {
    api.on("POST /api/v1/imports/splitwise", () => ({
      data: { importId: "imp-1", rows: 1, alreadyImported: 0, changed: 0, applied: 0, suggested: 1, unmatched: 0, noEffect: 0, unreadableRows: 0 },
    }));
    await page.goto("/import");
    await page.getByLabel("Splitwise export", { exact: true }).setInputFiles({ name: "group.csv", mimeType: "text/csv", buffer: exportCsv });
    const who = page.getByRole("combobox", { name: "Which one is you?" });
    await expect(who.getByRole("option")).toHaveText(["Choose your name", "Dev P.", "Asha R.", "<b>Kabir</b>"]);
    await expect(page.locator("main b", { hasText: "Kabir" })).toHaveCount(0);
    await who.selectOption("Dev P.");
    await page.getByRole("button", { name: "Import from Splitwise", exact: true }).click();
    const done = page.getByRole("status", { name: "Splitwise import result" });
    await expect(done).toContainText("1 row read, 0 applied");
    await expect.poll(() => api.callsTo("POST", "/api/v1/imports/splitwise").length).toBe(1);
    await done.getByRole("link", { name: "Review the matches →" }).click();
    await expect(page).toHaveURL(/\/review$/);
  });

  test("a file that isn't an export is refused in the page, before any upload", async ({ signedIn: page, api }) => {
    await page.goto("/import");
    await page.getByLabel("Splitwise export", { exact: true }).setInputFiles({ name: "bank.csv", mimeType: "text/csv", buffer: Buffer.from("Date,Amount\n2026-10-03,100") });
    await expect(alertOf(page)).toContainText("doesn't look like a Splitwise export");
    expect(api.callsTo("POST", "/api/v1/imports/splitwise")).toEqual([]);
  });

  test("the server's refusal is shown in words", async ({ signedIn: page, api }) => {
    api.on("POST /api/v1/imports/splitwise", () => ({ status: 422, error: { code: "SPLITWISE_ME_UNKNOWN", message: "Your name isn't a column in this export" } }));
    await page.goto("/import");
    await page.getByLabel("Splitwise export", { exact: true }).setInputFiles({ name: "group.csv", mimeType: "text/csv", buffer: exportCsv });
    await page.getByRole("combobox", { name: "Which one is you?" }).selectOption("Asha R.");
    await page.getByRole("button", { name: "Import from Splitwise", exact: true }).click();
    await expect(alertOf(page)).toContainText("Your name isn't a column in this export");
  });

  test("Review: confirm, reject and undo are sent; untrusted text stays text", async ({ signedIn: page, api }) => {
    withEntries(api);
    await page.goto("/review");
    await expect(page.getByText(/Friends owe you ₹1,200/)).toBeVisible();
    const bill = page.getByRole("listitem", { name: "Your share of a bill you paid?" });
    await expect(bill).toContainText("your share ₹1,000 of ₹4,000");
    await bill.getByRole("button", { name: "Yes, that's it", exact: true }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/splitwise/entries/sw-1/confirm").length).toBe(1);
    const payback = page.getByRole("listitem", { name: "A friend paying you back?" });
    await expect(payback).toContainText(HOSTILE);
    await payback.getByRole("button", { name: "Not this one", exact: true }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/splitwise/entries/sw-2/reject").length).toBe(1);
    await page.getByText("Applied (1)").click();
    await page.getByRole("list", { name: "Applied Splitwise entries" }).getByRole("button", { name: "Undo", exact: true }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/splitwise/entries/sw-5/undo").length).toBe(1);
    await expect(page.locator("main img")).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
  });

  test("no names, descriptions or tokens are kept in the browser's storage", async ({ signedIn: page, api }) => {
    withEntries(api);
    await page.goto("/review");
    await expect(page.getByRole("listitem", { name: "Your share of a bill you paid?" })).toBeVisible();
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
    expect(stored).not.toContain("Dinner at Toit");
    expect(stored).not.toContain("onerror");
    expect(stored).not.toContain("e2e-session");
  });

  test("with Splitwise entries Review is accessible", async ({ signedIn: page, api }) => {
    withEntries(api);
    await page.goto("/review");
    await expect(page.getByRole("list", { name: "Splitwise matches to review" })).toBeVisible();
    await expectAccessible(page);
  });

  test("signed out, the import and Review pages go to the login page", async ({ page }) => {
    for (const path of ["/import", "/review"]) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/login$/);
    }
  });

  test("an expired session that can't be refreshed ends at login, and nothing is sent", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/splitwise/entries", () => ({ status: 401, error: { code: "AUTH_001", message: "Token expired" } }))
      .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/review");
    await expect(page).toHaveURL(/\/login$/);
    expect(api.calls.filter((c) => c.method === "POST" && c.path.startsWith("/api/v1/splitwise"))).toEqual([]);
  });
});
