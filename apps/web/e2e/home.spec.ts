import { expect, mainNav, test } from "./support/fixtures";
import type { Page } from "@playwright/test";

/** Console Home in a real browser (AOS-303): the period lives in the URL. */
const monthName = (offset: number) => {
  const d = new Date();
  return new Intl.DateTimeFormat("en-IN", { month: "long", year: "numeric" }).format(new Date(d.getFullYear(), d.getMonth() + offset, 1));
};
const label = (page: Page) => page.getByRole("main").getByRole("heading", { level: 2 }).first();

test.describe("console Home", () => {
  test("shows the headline, the review banner, categories, merchants, latest and the week chart", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/transactions/suggestions", () => ({ data: [{}, {}] }));
    await page.goto("/");
    await expect(label(page)).toHaveText(monthName(0));
    await expect(page.getByText("₹48,250")).toBeVisible();
    await expect(page.getByRole("link", { name: /2 to review — they change your totals/ })).toBeVisible();
    await expect(page.getByRole("region", { name: "Where it went" }).getByText("Shopping")).toBeVisible();
    await expect(page.getByRole("region", { name: "Top merchants" }).getByText("Amazon")).toBeVisible();
    // Month view (AOS-304): a running-total line and day-by-day columns, both drawn by Recharts.
    await expect(page.getByRole("region", { name: "Running total" }).getByRole("img", { name: /^Spent ₹48,250 by / })).toBeVisible();
    await expect(page.locator(".recharts-line-curve").first()).toBeVisible();
    await expect(page.getByRole("region", { name: "Day by day" }).locator(".recharts-bar-rectangle").first()).toBeVisible();
    await expect(page.getByRole("region", { name: "This week" })).toHaveCount(0);
  });

  test("a week shows the week's columns instead of the month charts", async ({ signedIn: page }) => {
    await page.goto("/?p=week");
    await expect(page.getByRole("img", { name: /Spending this week: most on/ })).toBeVisible();
    await expect(page.locator(".recharts-bar-rectangle").first()).toBeVisible();
    await expect(page.getByRole("region", { name: "Running total" })).toHaveCount(0);
  });

  test("the running total can be read as a table", async ({ signedIn: page }) => {
    await page.goto("/");
    const running = page.getByRole("region", { name: "Running total" });
    await running.getByRole("button", { name: "View as table" }).click();
    await expect(running.getByRole("table", { name: "Running total" })).toBeVisible();
    await running.getByRole("button", { name: "View as chart" }).click();
    await expect(running.getByRole("img")).toBeVisible();
  });

  test("◀ ◀ ▶ then back, back, forward, refresh: every step is the right month in the URL", async ({ signedIn: page, api }) => {
    await page.goto("/");
    const next = page.getByRole("button", { name: "Next period" });
    await expect(next).toBeDisabled();
    await page.getByRole("button", { name: "Previous period" }).click();
    await expect(label(page)).toHaveText(monthName(-1));
    await expect(page).toHaveURL(/\?p=month&d=\d{4}-\d{2}-01$/);
    await page.getByRole("button", { name: "Previous period" }).click();
    await expect(label(page)).toHaveText(monthName(-2));
    await expect(page.getByText("Spent", { exact: true })).toBeVisible(); // a past month isn't "so far"
    await next.click();
    await expect(label(page)).toHaveText(monthName(-1));
    await page.goBack();
    await expect(label(page)).toHaveText(monthName(-2));
    await page.goBack();
    await expect(label(page)).toHaveText(monthName(-1));
    await page.goForward();
    await expect(label(page)).toHaveText(monthName(-2));
    await page.reload();
    await expect(label(page)).toHaveText(monthName(-2));
    const asked = api.calls.filter((c) => c.path === "/api/v1/analytics/summary").length;
    expect(asked).toBeGreaterThan(4); // each period asked for its own numbers
  });

  test("Day / Week / Month switch and survive Back", async ({ signedIn: page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: "Week" }).click();
    await expect(label(page)).toHaveText("This week");
    await expect(page).toHaveURL(/\?p=week&d=/);
    await page.getByRole("button", { name: "Day" }).click();
    await expect(label(page)).toHaveText("Today");
    await page.getByRole("button", { name: "Previous period" }).click();
    await expect(label(page)).toHaveText("Yesterday");
    await page.goBack();
    await expect(label(page)).toHaveText("Today");
    await page.goBack();
    await expect(label(page)).toHaveText("This week");
    await page.goBack();
    await expect(label(page)).toHaveText(monthName(0));
  });

  test("a bad URL falls back to this month and says why", async ({ signedIn: page }) => {
    await page.goto("/?p=decade&d=tomorrow");
    await expect(page.getByRole("main").getByRole("status")).toContainText("'decade' isn't a period");
    await expect(label(page)).toHaveText(monthName(0));
  });

  test("the period survives leaving Home and coming back", async ({ signedIn: page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: "Previous period" }).click();
    await expect(label(page)).toHaveText(monthName(-1));
    await mainNav(page).getByRole("link", { name: /^Activity/ }).click();
    await expect(page).toHaveURL(/\/transactions$/);
    await page.goBack();
    await expect(label(page)).toHaveText(monthName(-1));
  });

  test("a failing section can be retried while the rest stays", async ({ signedIn: page, api }) => {
    let n = 0;
    api.on("GET /api/v1/analytics/categories", () => (++n === 1 ? { status: 400, error: { code: "VALIDATION_003", message: "Categories broke" } } : { data: { categories: [] } }));
    await page.goto("/");
    const where = page.getByRole("region", { name: "Where it went" });
    await expect(where.getByText(/Categories broke/)).toBeVisible();
    await expect(page.getByText("₹48,250")).toBeVisible();
    await where.getByRole("button", { name: "Try again" }).click();
    await expect(where.getByText("Nothing spent in this period.")).toBeVisible();
  });
});
