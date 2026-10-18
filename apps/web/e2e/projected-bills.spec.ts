import { expect, test } from "./support/fixtures";
import { forecastWithProjectedBills, projectedBills } from "./support/mock-api";
import type { Page } from "@playwright/test";

/** Home → Month-end forecast → bills that usually come but haven't yet (AOS-721), in a real browser. */
const card = (page: Page) => page.getByRole("region", { name: "Month-end forecast" });

test.describe("projected bills in the forecast", () => {
  test("the month-end working shows the bills not arrived yet, each with its usual amount and day", async ({ signedIn: page }) => {
    await page.goto("/");
    await expect(card(page).getByText("About ₹63,680.50", { exact: true })).toBeVisible();
    await card(page).getByText("How we got this").first().click();
    await expect(card(page).getByText("+ Bills not arrived yet: ₹2,140")).toBeVisible();
    const items = card(page).getByRole("list", { name: "Bills not arrived yet" }).first();
    await expect(items.getByRole("listitem")).toHaveText([
      "Sample Power Co · expected 24 Oct · ₹1,240 · usual amount, billed 4 months running",
      "Example Mobile · expected 27 Oct · ₹900 · usual amount, billed 3 months running",
    ]);
    await expect(card(page).getByText("= About ₹63,680.50 by 31 Oct")).toBeVisible();
  });

  test("money left until salary counts them too", async ({ signedIn: page }) => {
    await page.goto("/");
    await expect(card(page).getByText("₹35,278.75 left from this salary")).toBeVisible();
    await card(page).getByText("How we got this").nth(1).click();
    await expect(card(page).getByText("of which Bills not arrived yet: ₹2,140")).toBeVisible();
    const line = card(page).getByRole("listitem").filter({ hasText: "of which Bills not arrived yet" });
    await expect(line.getByRole("list", { name: "Bills not arrived yet" }).getByRole("listitem")).toHaveCount(2);
  });

  test("a biller name from email or SMS is shown as text, never run", async ({ signedIn: page, api }) => {
    let ran = false;
    await page.exposeFunction("__pwned", () => { ran = true; });
    const evil = '<img src=x onerror="window.__pwned()">';
    const hostile = { ...projectedBills, amount: "1240.00", items: [{ ...projectedBills.items[0], name: evil }] };
    api.on("GET /api/v1/forecast", () => ({ data: forecastWithProjectedBills(hostile) }));
    await page.goto("/");
    await card(page).getByText("How we got this").first().click();
    await expect(card(page).getByText(`${evil} · expected 24 Oct · ₹1,240`, { exact: false }).first()).toBeVisible();
    expect(await card(page).locator("img, script").count()).toBe(0);
    expect(ran).toBe(false);
  });
});
