import { cx } from "../test/coachFixtures";
import { expect, expectAccessible, test } from "./support/fixtures";

/** The savings-rate headline on Home (AOS-908): rate and trend, what leaks could become, assumptions, attacks. */
test.describe("Savings rate", () => {
  test("Home leads with the savings rate and its trend", async ({ signedIn: page }) => {
    await page.goto("/");
    const card = page.getByRole("region", { name: "Savings rate" });
    await expect(card.getByText("31%", { exact: true })).toBeVisible();
    await expect(card.getByText(/▼ 9 points worse than September/)).toBeVisible();
    await card.getByRole("button", { name: "View months as table" }).click();
    await expect(card.getByRole("table", { name: "Savings rate by month" })).toBeVisible();
    await expectAccessible(page);
  });

  test("the projection shows its assumptions and recalculates with new ones", async ({ signedIn: page, api }) => {
    await page.goto("/");
    const card = page.getByRole("region", { name: "Savings rate" });
    await card.getByText("What October's leaks could become").click();
    await expect(card.getByText(/In 10 years: ₹12,89,482/)).toBeVisible();
    await card.getByLabel("Expected return, percent a year").fill("8");
    await card.getByRole("button", { name: "Recalculate" }).click();
    await expect.poll(() => api.calls.some((c) => c.path === "/api/v1/coach/savings")).toBe(true);
  });

  test("too little data says what's needed", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/coach/savings", () => ({ data: cx.noSavings() }));
    await page.goto("/");
    await expect(page.getByRole("region", { name: "Savings rate" }).getByText(/Not enough data yet/)).toBeVisible();
  });
});

test.describe("Savings rate: attacks (D-100)", () => {
  test("a refused session ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/coach/savings", () => ({ status: 401, error: { code: "AUTH_002", message: "Token invalid" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("a server error shows inline and nothing else breaks", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/coach/savings", () => ({ status: 500, error: { code: "SERVER_001", message: "<b>down</b>" } }));
    await page.goto("/");
    const card = page.getByRole("region", { name: "Savings rate" });
    await expect(card.getByText("<b>down</b>", { exact: false })).toBeVisible();
    expect(await card.locator("b").count()).toBe(0);
  });
});
