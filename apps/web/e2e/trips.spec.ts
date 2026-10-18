import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { MockApi } from "./support/mock-api";

/** Trips in a real browser (AOS-910): list → detail, edits sent, year in the URL, untrusted text, sign-in. */
const summary = (over: Record<string, unknown> = {}) => ({
  id: "trip-1", name: "Trip, 24 Oct – 30 Oct 2026", named: false, start: "2026-10-24", end: "2026-10-30", total: "5882.00",
  transactionCount: 2, modes: ["flight"], legs: [{ date: "2026-10-24", brand: "IndiGo", mode: "flight", transactionId: "t-1" }], ...over,
});

function withTrips(api: MockApi, name = "Trip, 24 Oct – 30 Oct 2026") {
  api.on("GET /api/v1/trips", (_r, u) => ({ data: { year: Number(u.searchParams.get("year") ?? 2026), total: "5882.00", currency: "INR", trips: [summary({ name })] } }))
    .on("GET /api/v1/trips/:id", () => ({ data: { trip: summary({ name }), members: [
      { role: "booking", refunded: "0.00", transaction: fx.transaction({ id: "t-1", merchantName: "IndiGo", amount: "5432.00" }) },
      { role: "spend", refunded: "0.00", transaction: fx.transaction({ id: "t-2", merchantName: "Uber", amount: "450.00" }) },
    ] } }))
    .on("GET /api/v1/trips/:id/candidates", () => ({ data: [fx.transaction({ id: "t-9", merchantName: "Sample Stay", amount: "3000.00" })] }))
    .on("PATCH /api/v1/trips/:id", () => ({ data: summary({ name: "Home" }) }))
    .on("POST /api/v1/trips/:id/transactions", () => ({ data: summary() }))
    .on("DELETE /api/v1/trips/:id/transactions/:t", () => ({ data: summary() }))
    .on("DELETE /api/v1/trips/:id", () => ({ data: { id: "trip-1" } }));
}

test.describe("Trips", () => {
  test("the year's trips, a trip's payments, and every edit is sent", async ({ signedIn: page, api }) => {
    withTrips(api);
    await page.goto("/trips");
    await expect(page.getByRole("heading", { name: "Trips", exact: true })).toBeVisible();
    await expectAccessible(page);
    await page.getByRole("link", { name: /Trip, 24 Oct – 30 Oct 2026/ }).click();
    await expect(page).toHaveURL(/\/trips\/trip-1$/);
    await expect(page.getByRole("list", { name: "Payments in this trip" }).getByRole("listitem")).toHaveCount(2);
    await page.getByRole("button", { name: "Rename", exact: true }).click();
    await page.getByLabel("Trip name").fill("Home");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await page.getByRole("button", { name: "Remove Uber from this trip" }).click();
    await page.getByRole("button", { name: "Add a payment" }).click();
    await page.getByRole("button", { name: "Add Sample Stay to this trip" }).click();
    await expect.poll(() => api.callsTo("PATCH", "/api/v1/trips/trip-1").length).toBe(1);
    await expect.poll(() => api.callsTo("DELETE", "/api/v1/trips/trip-1/transactions/t-2").length).toBe(1);
    await expect.poll(() => api.callsTo("POST", "/api/v1/trips/trip-1/transactions").map((c) => c.body)).toEqual([{ transactionId: "t-9" }]);
    await page.getByRole("button", { name: "Not a trip" }).click();
    await expect(page).toHaveURL(/\/trips$/);
  });

  test("the year lives in the URL; back restores it", async ({ signedIn: page, api }) => {
    withTrips(api);
    await page.goto("/trips?year=2026");
    await page.getByRole("button", { name: "Trips in 2025" }).click();
    await expect(page).toHaveURL(/\/trips\?year=2025$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/trips\?year=2026$/);
  });

  test("an untrusted trip name renders as text", async ({ signedIn: page, api }) => {
    withTrips(api, "<img src=x onerror=alert(1)>");
    await page.goto("/trips");
    await expect(page.getByText("<img src=x onerror=alert(1)>")).toBeVisible();
    await expect(page.locator("main img[src='x']")).toHaveCount(0);
  });

  test("signed out, trips go to sign-in; an expired session that can't refresh too", async ({ page }) => {
    await page.goto("/trips/trip-1");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("a forged or expired token is refused", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/trips", () => ({ status: 401, error: { code: "AUTH_001", message: "Token invalid" } }))
      .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/trips");
    await expect(page).toHaveURL(/\/login$/);
  });
});
