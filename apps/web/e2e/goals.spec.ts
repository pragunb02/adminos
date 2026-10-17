import { cx } from "../test/coachFixtures";
import { expect, expectAccessible, test } from "./support/fixtures";

/** Goals in a real browser (AOS-907): progress and streak on the leaks page and Home, setting and deleting, attacks. */
test.describe("Goals", () => {
  test("the leaks page shows each goal's weeks and streak, and sets a new goal", async ({ signedIn: page, api }) => {
    await page.goto("/leaks");
    const goals = page.getByRole("region", { name: "Goals" });
    await expect(goals.getByText("This week: ₹300 of ₹479 · 3-day no-spend streak (best 7)")).toBeVisible();
    await goals.getByLabel("Goal habit").selectOption("travel");
    await goals.getByLabel("Goal type").selectOption("cap_amount");
    await goals.getByLabel("Keep under rupees").fill("5000");
    await goals.getByRole("button", { name: "Set goal" }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/coach/goals").map((c) => c.body)).toEqual([{ lens: "travel", kind: "cap_amount", value: "5000", period: "weekly" }]);
    await goals.getByRole("button", { name: "Delete goal: Cut Quick commerce by 30% (to ₹479 a week)" }).click();
    await expect.poll(() => api.callsTo("DELETE", "/api/v1/coach/goals/goal-1").length).toBe(1);
    await expectAccessible(page);
  });

  test("Home shows the goal and links to the leaks page", async ({ signedIn: page }) => {
    await page.goto("/");
    const card = page.getByRole("region", { name: "Goals" });
    await expect(card.getByText("Cut Quick commerce by 30% (to ₹479 a week)")).toBeVisible();
    await card.getByRole("link", { name: "All ›" }).click();
    await expect(page).toHaveURL(/\/leaks$/);
  });
});

test.describe("Goals: attacks (D-100)", () => {
  test("a refused session ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/coach/goals", () => ({ status: 401, error: { code: "AUTH_002", message: "Token invalid" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/leaks");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("goal labels from the data render as text", async ({ signedIn: page, api }) => {
    const hostile = `<img src=x onerror="window.__pwned=1">`;
    api.on("GET /api/v1/coach/goals", () => ({ data: [cx.goal({ label: hostile })] }));
    await page.goto("/leaks");
    await expect(page.getByRole("region", { name: "Goals" }).getByText(hostile, { exact: false }).first()).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
    expect(await page.locator("img[src='x']").count()).toBe(0);
  });
});
