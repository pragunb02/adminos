import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { Page } from "@playwright/test";

/** Home → Month-end forecast in a real browser (AOS-714). Every number comes from the mocked API. */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));
const card = (page: Page) => page.getByRole("region", { name: "Month-end forecast" });

test.describe("month-end forecast", () => {
  test("shows the forecast, its range and money left, and opens how it was worked out", async ({ signedIn: page }) => {
    await page.goto("/");
    await expect(card(page).getByText("About ₹61,540.50")).toBeVisible();
    await expect(card(page).getByText("Likely ₹57,200 to ₹66,900 (8 in 10, from your last 7 months)")).toBeVisible();
    await expect(card(page).getByText("₹37,418.75 left from this salary")).toBeVisible();
    await card(page).getByText("How we got this").first().click();
    await expect(card(page).getByText("= About ₹61,540.50 by 31 Oct")).toBeVisible();
    await expectAccessible(page);
  });

  test("too little history says so instead of guessing", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/forecast", () => ({ data: fx.forecast({ status: "not_enough_history", history: { days: 40, neededDays: 90, readyOn: "2026-11-25" }, monthEnd: null, salary: null }) }));
    await page.goto("/");
    await expect(card(page).getByText(/Not enough history to forecast yet \(40 of 90 days\)/)).toBeVisible();
  });

  test("a failed forecast can be retried while the rest of Home stays", async ({ signedIn: page, api }) => {
    let n = 0;
    api.on("GET /api/v1/forecast", () => (++n === 1 ? { status: 400, error: { code: "VALIDATION_003", message: "Forecast is down" } } : { data: fx.forecast() }));
    await page.goto("/");
    await expect(alertOf(page).filter({ hasText: "Forecast is down" })).toBeVisible();
    await expect(page.getByText("₹48,250").first()).toBeVisible();
    await card(page).getByRole("button", { name: "Try again", exact: true }).click();
    await expect(card(page).getByText("About ₹61,540.50")).toBeVisible();
  });

  test("a past month has no forecast", async ({ signedIn: page }) => {
    await page.goto("/");
    await expect(card(page)).toBeVisible();
    await page.getByRole("button", { name: "Previous period" }).click();
    await expect(card(page)).toHaveCount(0);
  });
});
