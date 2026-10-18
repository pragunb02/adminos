import { cx } from "../test/coachFixtures";
import { expect, expectAccessible, test } from "./support/fixtures";

/** Limits in a real browser (AOS-904): learning month, suggestions, Settings management, attacks. */
test.describe("Limits", () => {
  test("during the learning month the leaks page only says how far along", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/coach/limits", () => ({ data: cx.limits() }));
    await page.goto("/leaks");
    await expect(page.getByRole("region", { name: "Limits" }).getByText("Learning your habits: 12 of 30 days")).toBeVisible();
    await expect(page.getByRole("button", { name: "Accept", exact: true })).toHaveCount(0);
  });

  test("a suggested limit shows its working and is accepted", async ({ signedIn: page, api }) => {
    await page.goto("/leaks");
    const s = page.getByRole("group", { name: "Suggested limit for Food delivery" });
    await s.getByText("How we got this").click();
    await expect(s.getByText("Median ₹1,250, minus 10%, rounded down to the nearest ₹50.")).toBeVisible();
    await s.getByRole("button", { name: "Accept", exact: true }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/coach/limits").map((c) => c.body)).toEqual([{ scope: { kind: "lens", value: "food_delivery" }, period: "weekly", amount: "1100.00" }]);
    await expectAccessible(page);
  });

  test("Settings changes, deletes and adds limits, and switches alerts", async ({ signedIn: page, api }) => {
    await page.goto("/settings");
    const coach = page.getByRole("region", { name: "Spending coach" });
    await coach.getByRole("checkbox", { name: "Limit alerts at 50%, 80% and 100%" }).click();
    await expect.poll(() => api.callsTo("PATCH", "/api/v1/coach/settings").length).toBe(1);
    await coach.getByRole("button", { name: "Change Quick commerce weekly limit" }).click();
    await coach.getByLabel("New amount for Quick commerce").fill("2500");
    await coach.getByRole("button", { name: "Save", exact: true }).click();
    await expect.poll(() => api.callsTo("PATCH", "/api/v1/coach/limits/lim-1").map((c) => c.body)).toEqual([{ amount: "2500" }]);
    await coach.getByLabel("Limit on").selectOption("merchant");
    await coach.getByLabel("Merchant").fill("Zepto");
    await coach.getByLabel("Amount in rupees").fill("500");
    await coach.getByRole("button", { name: "Add limit" }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/coach/limits").length).toBe(1);
    await coach.getByRole("button", { name: "Delete Quick commerce weekly limit" }).click();
    await expect.poll(() => api.callsTo("DELETE", "/api/v1/coach/limits/lim-1").length).toBe(1);
  });
});

test.describe("Evening digest settings (AOS-905)", () => {
  test("the digest defaults to 21:30 and its time and options are saved", async ({ signedIn: page, api }) => {
    // The switches show what the server saved (they are not optimistic), so the mock keeps what was saved.
    let saved = { limitAlerts: true, eveningDigest: true, digestTime: "21:30", digestSkipZeroDay: false };
    api.on("GET /api/v1/coach/settings", () => ({ data: saved }))
       .on("PATCH /api/v1/coach/settings", (r) => { saved = { ...saved, ...(r.postDataJSON() as Partial<typeof saved>) }; return { data: saved }; });
    await page.goto("/settings");
    const coach = page.getByRole("region", { name: "Spending coach" });
    const time = coach.getByLabel("Digest time");
    await expect(time).toHaveValue("21:30");
    await time.fill("20:15");
    await coach.getByRole("heading", { name: "Spending coach" }).click(); // leave the field: the time saves when it loses focus
    await expect.poll(() => api.callsTo("PATCH", "/api/v1/coach/settings").length).toBe(1);
    // click, not check(): the box turns on once the server has saved it, not on the click itself.
    const skip = coach.getByRole("checkbox", { name: "Skip on a day with no spending" });
    await skip.click();
    await expect(skip).toBeChecked();
    await expect(time).toHaveValue("20:15");
    await expect.poll(() => api.callsTo("PATCH", "/api/v1/coach/settings").map((c) => c.body)).toEqual([{ digestTime: "20:15" }, { digestSkipZeroDay: true }]);
  });
});

test.describe("Limits: attacks (D-100)", () => {
  test("signed out, Settings and the leaks page go to login", async ({ page }) => {
    for (const path of ["/settings", "/leaks"]) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/login$/);
    }
  });

  test("a session the API refuses ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/coach/limits", () => ({ status: 401, error: { code: "AUTH_002", message: "Token invalid" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/leaks");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("limit labels from the data render as text", async ({ signedIn: page, api }) => {
    const hostile = `<img src=x onerror="window.__pwned=1">`;
    api.on("GET /api/v1/coach/limits", () => ({ data: cx.limits({ ...cx.limitsReady(), limits: [cx.limit({ label: hostile })], suggestions: [] }) }));
    await page.goto("/leaks");
    await expect(page.getByText(hostile, { exact: false }).first()).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
    expect(await page.locator("img[src='x']").count()).toBe(0);
  });
});
