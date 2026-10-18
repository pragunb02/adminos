import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { Page } from "@playwright/test";

/** Cash from words (AOS-718) in a real browser: read → check → save, and the attack cases. */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));
const sheet = (page: Page) => page.getByRole("dialog", { name: "Add cash spending" });

async function readWords(page: Page, text: string) {
  await page.goto("/");
  await page.getByRole("button", { name: "Add cash spending" }).click();
  await sheet(page).getByLabel("Or say it in words").fill(text);
  await sheet(page).getByRole("button", { name: "Read it", exact: true }).click();
  await expect(sheet(page).getByRole("heading", { name: "Check before saving" })).toBeVisible();
}

test.describe("cash from words", () => {
  test("words become entries to check; saving sends each once, on its day", async ({ signedIn: page, api }) => {
    await readWords(page, "200 chai and kal 150 raju, samosa");
    expect(api.callsTo("POST", "/api/v1/transactions/cash/parse")[0].body).toEqual({ text: "200 chai and kal 150 raju, samosa" });
    await expect(sheet(page).getByText("“samosa”: no amount")).toBeVisible();
    await sheet(page).getByLabel("Category for entry 2").selectOption("health");
    await expectAccessible(page);
    await sheet(page).getByRole("button", { name: "Save 2 entries", exact: true }).click();
    await expect(page.getByRole("status")).toHaveText("2 cash entries saved (₹350)");
    const saves = api.callsTo("POST", "/api/v1/transactions/cash").map((c) => c.body as Record<string, string>);
    expect(saves).toMatchObject([{ amount: "200", category: "food", date: "2026-10-17" }, { amount: "150", category: "health", date: "2026-10-16" }]);
    expect(new Set(saves.map((s) => s.clientRef)).size).toBe(2);
  });

  test("a reading that fails says why and keeps the words", async ({ signedIn: page, api }) => {
    api.on("POST /api/v1/transactions/cash/parse", () => ({ status: 400, error: { code: "VALIDATION_001", message: "text: at most 500 characters" } }));
    await page.goto("/");
    await page.getByRole("button", { name: "Add cash spending" }).click();
    await sheet(page).getByLabel("Or say it in words").fill("chai 40");
    await sheet(page).getByRole("button", { name: "Read it", exact: true }).click();
    await expect(alertOf(page).filter({ hasText: "text: at most 500 characters" })).toBeVisible();
    await expect(sheet(page).getByLabel("Or say it in words")).toHaveValue("chai 40");
  });

  test("untrusted text from the API is shown as text, never run", async ({ signedIn: page, api }) => {
    let ran = false;
    await page.exposeFunction("__pwned", () => { ran = true; });
    const evil = '<img src=x onerror="window.__pwned()">';
    api.on("POST /api/v1/transactions/cash/parse", () => ({ data: fx.cashParse({
      entries: [{ ...fx.cashParse().entries[0], note: evil }],
      unparsed: [{ text: "<script>window.__pwned()</script>", reason: "too_large" }],
    }) }));
    await readWords(page, "anything");
    await expect(sheet(page).getByLabel("Note for entry 1")).toHaveValue(evil);
    await expect(sheet(page).getByText("“<script>window.__pwned()</script>”: over ₹1 crore")).toBeVisible();
    expect(await sheet(page).locator("img, script").count()).toBe(0);
    expect(ran).toBe(false);
  });

  test("a session that can't be refreshed ends at login instead of reading", async ({ signedIn: page, api }) => {
    await page.goto("/");
    api.on("POST /api/v1/transactions/cash/parse", () => ({ status: 401, error: { code: "AUTH_001", message: "Token expired" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.getByRole("button", { name: "Add cash spending" }).click();
    await sheet(page).getByLabel("Or say it in words").fill("chai 40");
    await sheet(page).getByRole("button", { name: "Read it", exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test("no session token or secret is readable by the page", async ({ signedIn: page }) => {
    await readWords(page, "200 chai");
    // The session cookie is HttpOnly in the real app; here we check nothing token-like reached the DOM or storage.
    const leaked = await page.evaluate(() => [document.documentElement.outerHTML, JSON.stringify(localStorage), JSON.stringify(sessionStorage)].join("\n"));
    expect(leaked).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);
    expect(leaked).not.toContain("e2e-session");
  });
});
