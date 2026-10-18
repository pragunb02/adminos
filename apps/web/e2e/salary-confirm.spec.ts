import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import { sx } from "../test/salaryFixtures";
import type { Page } from "@playwright/test";
import type { SalaryView } from "@/lib/salary";

/**
 * Home → Month-end forecast → Your salary (AOS-722) in a real browser: confirm or correct the detected salary.
 * "Your salary" sits in the salary part of the card ("How we got this"); every answer comes from the mocked API.
 */
const card = (page: Page) => page.getByRole("region", { name: "Month-end forecast" });
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));
const PATH = "/api/v1/forecast/salary";

/** Opens the salary working if "Your salary" isn't already showing, and returns it. */
async function yourSalary(page: Page) {
  await page.goto("/");
  await expect(card(page).getByText("How we got this").first()).toBeVisible();
  const r = card(page).getByRole("region", { name: "Your salary" });
  if (!(await r.isVisible())) await card(page).getByText("How we got this").last().click();
  await expect(r).toBeVisible();
  return r;
}

test.describe("your salary", () => {
  test("shows the detected salary; confirming keeps it and refreshes the forecast", async ({ signedIn: page, api }) => {
    const r = await yourSalary(page);
    await expect(r.getByText(/₹1,00,000 from ACME TECHNOLOGIES around the 30th/)).toBeVisible();
    const before = api.callsTo("GET", "/api/v1/forecast").length;
    await r.getByRole("button", { name: "Yes, that's right" }).click();
    await expect(r.getByText(/Confirmed by you/)).toBeVisible();
    expect(api.callsTo("PUT", PATH).map((c) => c.body)).toEqual([{ action: "confirm" }]);
    await expect.poll(() => api.callsTo("GET", "/api/v1/forecast").length).toBeGreaterThan(before);
    await expect(r.getByRole("button", { name: "Yes, that's right" })).toHaveCount(0);
    await expectAccessible(page);
  });

  test("correcting stores the typed payer, day and amount", async ({ signedIn: page, api }) => {
    const r = await yourSalary(page);
    await r.getByRole("button", { name: "Correct it" }).click();
    await r.getByRole("textbox", { name: "Payer" }).fill("Example Employer");
    await r.getByRole("textbox", { name: "Day of month" }).fill("1");
    await r.getByRole("textbox", { name: "Amount" }).fill("85,000");
    await expectAccessible(page);
    await r.getByRole("button", { name: "Save" }).click();
    await expect(r.getByText("₹85,000 from Example Employer around the 1st")).toBeVisible();
    await expect(r.getByText(/Corrected by you/)).toBeVisible();
    expect(api.callsTo("PUT", PATH).map((c) => c.body)).toEqual([{ action: "correct", payer: "Example Employer", day: 1, amount: "85000" }]);
  });

  test("with no salary found it says so, offers no confirm, and a correction sets one", async ({ signedIn: page, api }) => {
    const f = fx.forecast();
    api.on("GET /api/v1/forecast", () => ({ data: fx.forecast({ salary: { ...f.salary!, status: "unknown", reason: "amount_varies", untilSalary: null, expectedOn: null } }) }));
    let view: SalaryView = { detected: null, detectedReason: "amount_varies", choice: null };
    api.on(`GET ${PATH}`, () => ({ data: view }))
       .on(`PUT ${PATH}`, () => { view = { ...view, choice: sx.choice({ payer: "Example Employer", day: 7, amount: "50000.00" }) }; return { data: view }; });
    const r = await yourSalary(page);
    await expect(r.getByText(/We couldn't find your salary/)).toBeVisible();
    await expect(r.getByRole("button", { name: "Yes, that's right" })).toHaveCount(0);
    await r.getByRole("button", { name: "Correct it" }).click();
    await r.getByRole("textbox", { name: "Payer" }).fill("Example Employer");
    await r.getByRole("textbox", { name: "Day of month" }).fill("7");
    await r.getByRole("textbox", { name: "Amount" }).fill("50000");
    await r.getByRole("button", { name: "Save" }).click();
    await expect(r.getByText(/₹50,000 from Example Employer around the 7th/)).toBeVisible();
  });

  test("a confirmed salary is marked confirmed beside the salary line", async ({ signedIn: page, api }) => {
    const f = fx.forecast();
    api.on("GET /api/v1/forecast", () => ({ data: fx.forecast({ salary: { ...f.salary!, source: "confirmed", usualDay: 30 } }) }));
    await page.goto("/");
    const line = card(page).getByText(/^Salary expected Fri, 30 Oct \(ACME TECHNOLOGIES\)/);
    await expect(line.getByText("confirmed", { exact: true })).toBeVisible();
  });

  test("a bad value is caught before anything is sent", async ({ signedIn: page, api }) => {
    const r = await yourSalary(page);
    await r.getByRole("button", { name: "Correct it" }).click();
    await r.getByRole("textbox", { name: "Day of month" }).fill("32");
    await r.getByRole("button", { name: "Save" }).click();
    await expect(r.getByRole("alert")).toHaveText("Day of month: a whole number from 1 to 31.");
    expect(api.callsTo("PUT", PATH)).toHaveLength(0);
  });
});

test.describe("your salary: attacks (D-100)", () => {
  test("a payer name from bank messages is shown as text, never run", async ({ signedIn: page, api }) => {
    let ran = false;
    await page.exposeFunction("__pwned", () => { ran = true; });
    const evil = '<img src=x onerror="window.__pwned()">';
    api.on(`GET ${PATH}`, () => ({ data: sx.view({ detected: sx.detected({ payer: evil }) }) }));
    const r = await yourSalary(page);
    await expect(r.getByText(`from ${evil} around the 30th`, { exact: false })).toBeVisible();
    await r.getByRole("button", { name: "Correct it" }).click();
    await expect(r.getByRole("textbox", { name: "Payer" })).toHaveValue(evil);
    expect(await card(page).locator("img, script").count()).toBe(0);
    expect(ran).toBe(false);
  });

  test("the API's refusal is shown in an alert and the form stays open", async ({ signedIn: page, api }) => {
    api.on(`PUT ${PATH}`, () => ({ status: 400, error: { code: "VALIDATION_001", message: "payer: letters, digits and punctuation only" } }));
    const r = await yourSalary(page);
    await r.getByRole("button", { name: "Correct it" }).click();
    await r.getByRole("button", { name: "Save" }).click();
    await expect(alertOf(page).filter({ hasText: "payer: letters, digits and punctuation only" })).toBeVisible();
    await expect(r.getByRole("form", { name: "Correct your salary" })).toBeVisible();
  });

  test("too many changes says so in words", async ({ signedIn: page, api }) => {
    api.on(`PUT ${PATH}`, () => ({ status: 429, error: { code: "RATE_001", message: "Too many salary changes. Try again in 30 seconds." } }));
    const r = await yourSalary(page);
    await r.getByRole("button", { name: "Yes, that's right" }).click();
    await expect(r.getByRole("alert")).toHaveText("Too many salary changes. Try again in 30 seconds.");
  });

  test("signed out, Home goes to login and the salary is never asked for", async ({ page, api }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("GET", PATH)).toHaveLength(0);
    expect(api.callsTo("PUT", PATH)).toHaveLength(0);
  });

  test("a forged or expired session the API refuses ends at login", async ({ signedIn: page, api }) => {
    api.on(`GET ${PATH}`, () => ({ status: 401, error: { code: "AUTH_002", message: "Token invalid" } }))
       .on("GET /api/v1/forecast", () => ({ status: 401, error: { code: "AUTH_002", message: "Token invalid" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("PUT", PATH)).toHaveLength(0);
  });

  test("the payer and amounts never reach storage or the URL", async ({ signedIn: page }) => {
    const r = await yourSalary(page);
    await r.getByRole("button", { name: "Correct it" }).click();
    await r.getByRole("textbox", { name: "Payer" }).fill("Example Employer");
    await r.getByRole("textbox", { name: "Day of month" }).fill("1");
    await r.getByRole("textbox", { name: "Amount" }).fill("85000");
    await r.getByRole("button", { name: "Save" }).click();
    await expect(r.getByText(/Corrected by you/)).toBeVisible();
    const seen = await page.evaluate(() => [location.href, JSON.stringify(localStorage), JSON.stringify(sessionStorage)].join("\n"));
    for (const secret of ["Example Employer", "ACME", "85000", "85,000", "100000", "1,00,000"]) expect(seen).not.toContain(secret);
  });
});
