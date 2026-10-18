import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { Page } from "@playwright/test";

/** Bank balances and money left from them, in a real browser (AOS-720). Every figure comes from the mocked API. */
const card = (page: Page) => page.getByRole("region", { name: "Bank balances" });
const forecast = (page: Page) => page.getByRole("region", { name: "Month-end forecast" });

test.describe("bank balances", () => {
  test("Home shows the counted total and each account's age, and links to all of them", async ({ signedIn: page }) => {
    await page.goto("/");
    await expect(card(page).getByText("₹41,300.50")).toBeVisible();
    await expect(card(page).getByText("16 days ago · too old to count (over 7 days) · from a bank SMS")).toBeVisible();
    await expect(card(page).getByText("Overdrawn ₹1,200")).toBeVisible();
    await card(page).getByRole("link", { name: "All ›" }).click();
    await expect(page).toHaveURL(/\/balances$/);
    await expect(page.getByRole("heading", { level: 2, name: "Bank balances" })).toBeVisible();
    await expect(page.getByText(/never by when it arrived/)).toBeVisible();
    await expectAccessible(page);
  });

  test("money left until salary starts from the balances and says how", async ({ signedIn: page, api }) => {
    const f = fx.forecast();
    api.on("GET /api/v1/forecast", () => ({ data: fx.forecast({ salary: { ...f.salary!, untilSalary: fx.balanceUntilSalary() } }) }));
    await page.goto("/");
    await expect(forecast(page).getByText("₹27,619.25 left before it comes")).toBeVisible();
    await forecast(page).getByText("How we got this").nth(1).click();
    await expect(forecast(page).getByText(/In your accounts: ₹41,300.50/)).toBeVisible();
    await expect(forecast(page).getByText(/Not counted: Account ••9012/)).toBeVisible();
  });

  test("nothing known says how a balance gets in", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/balances", () => ({ data: fx.balances({ accounts: [], total: "0.00", countedAccounts: 0 }) }));
    await page.goto("/balances");
    await expect(page.getByText(/No balance known yet/)).toBeVisible();
  });

  test("a failed load can be retried", async ({ signedIn: page, api }) => {
    let n = 0;
    api.on("GET /api/v1/balances", () => (++n === 1 ? { status: 400, error: { code: "VALIDATION_003", message: "Balances are down" } } : { data: fx.balances() }));
    await page.goto("/balances");
    await expect(page.getByRole("alert").filter({ hasText: "Balances are down" })).toBeVisible();
    await card(page).getByRole("button", { name: "Try again", exact: true }).click();
    await expect(card(page).getByText("₹41,300.50")).toBeVisible();
  });
});

test.describe("bank balances under attack", () => {
  test("a hostile bank name is shown as text, never run", async ({ signedIn: page, api }) => {
    let ran = false;
    await page.exposeFunction("__pwned", () => { ran = true; });
    const evil = '<img src=x onerror="window.__pwned()">';
    const f = fx.balances();
    api.on("GET /api/v1/balances", () => ({ data: fx.balances({ accounts: [{ ...f.accounts[0], bank: evil }] }) }));
    await page.goto("/balances");
    await expect(card(page).getByText(`${evil.toUpperCase()} ••1234`)).toBeVisible();
    expect(await card(page).locator("img, script").count()).toBe(0);
    expect(ran).toBe(false);
  });

  test("a forged or expired session that can't be refreshed ends at login, with no balance shown", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/balances", () => ({ status: 401, error: { code: "AUTH_001", message: "Token expired" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/balances");
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText("₹41,300.50")).toHaveCount(0);
  });

  test("signed out, the page goes to login and no balance is asked for", async ({ page, api }) => {
    await page.goto("/balances");
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("GET", "/api/v1/balances")).toHaveLength(0);
  });

  test("no balance or account number is kept in storage or the URL", async ({ signedIn: page }) => {
    await page.goto("/balances");
    await expect(card(page).getByText("₹41,300.50")).toBeVisible();
    const stored = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }));
    expect(stored).not.toMatch(/41300|42500|1234|5678/);
    expect(page.url()).not.toMatch(/41300|1234/);
  });
});
