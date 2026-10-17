import { cx } from "../test/coachFixtures";
import { expect, expectAccessible, test } from "./support/fixtures";

/** "Where it leaks" in a real browser (AOS-902): numbers link to Activity, URL state, lens edits with undo, attacks. */
const alertOf = (page: import("@playwright/test").Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));

test.describe("Where it leaks", () => {
  test("each lens shows its numbers; a merchant opens its transactions in Activity", async ({ signedIn: page, api }) => {
    await page.goto("/leaks?from=2026-10-12&to=2026-10-18");
    const qc = page.getByRole("region", { name: "Quick commerce" });
    await expect(qc.getByText("2 of 3 orders after 22:00 (67%)")).toBeVisible();
    await expect.poll(() => api.calls.some((c) => c.path === "/api/v1/coach/leaks")).toBe(true);
    await qc.getByRole("link", { name: "Zepto", exact: true }).click();
    await expect(page).toHaveURL(/\/transactions\?lens=quick_commerce&from=2026-10-12&to=2026-10-18&merchant=Zepto$/);
    await expect(page.getByRole("button", { name: "Remove filter Lens: Quick commerce" })).toBeVisible();
    await expect.poll(() => api.calls.some((c) => c.path === "/api/v1/coach/leaks/transactions")).toBe(true);
    await page.goBack();
    await expect(page).toHaveURL(/\/leaks\?from=2026-10-12&to=2026-10-18$/);
    await expectAccessible(page);
  });

  test("the period switcher and a custom range live in the URL", async ({ signedIn: page }) => {
    await page.goto("/leaks");
    await expect(page.getByRole("heading", { name: "Where it leaks" })).toBeVisible();
    await page.getByRole("button", { name: "Week", exact: true }).click();
    await expect(page).toHaveURL(/\/leaks\?p=week&d=\d{4}-\d{2}-\d{2}$/);
    await page.getByText("Custom range").click();
    await page.getByLabel("From date").fill("2026-09-01");
    await page.getByLabel("To date").fill("2026-09-30");
    await page.getByRole("button", { name: "Show", exact: true }).click();
    await expect(page).toHaveURL(/\/leaks\?from=2026-09-01&to=2026-09-30$/);
    await page.reload();
    await expect(page.getByText("2026-09-01 → 2026-09-30")).toBeVisible();
    await page.getByRole("button", { name: "Back to periods" }).click();
    await expect(page).toHaveURL(/\/leaks$/);
  });

  test("Not a leak takes a merchant out, and Undo puts it back", async ({ signedIn: page, api }) => {
    await page.goto("/leaks?from=2026-10-12&to=2026-10-18");
    await page.getByRole("button", { name: "Not a leak: take Blinkit out of Quick commerce" }).click();
    const toast = page.getByRole("status").filter({ hasText: "Blinkit is no longer counted in Quick commerce" });
    await expect(toast).toBeVisible();
    await toast.getByRole("button", { name: "Undo", exact: true }).click();
    await expect.poll(() => api.calls.filter((c) => c.path === "/api/v1/coach/lenses/quick_commerce/merchants").map((c) => c.method)).toEqual(["PUT", "DELETE"]);
  });

  test("a failing load shows its own error with a retry", async ({ signedIn: page, api }) => {
    // Down until the user retries: the app's own automatic retries must not hide the error.
    let healthy = false;
    api.on("GET /api/v1/coach/leaks", () => (healthy ? { data: cx.leaks() } : { status: 500, error: { code: "SERVER_001", message: "Leaks are down" } }));
    await page.goto("/leaks");
    await expect(alertOf(page)).toContainText("Leaks are down", { timeout: 15_000 });
    healthy = true;
    await alertOf(page).getByRole("button", { name: "Try again" }).click();
    await expect(page.getByRole("region", { name: "Quick commerce" })).toBeVisible();
  });

  test("Home links to the page, and so does More", async ({ signedIn: page }) => {
    await page.goto("/");
    await page.getByRole("region", { name: "Where it leaks" }).getByRole("link", { name: "All ›" }).click();
    await expect(page).toHaveURL(/\/leaks$/);
    await page.goto("/more");
    await page.getByRole("main").getByRole("link", { name: "Where it leaks" }).click();
    await expect(page).toHaveURL(/\/leaks$/);
  });
});

test.describe("Where it leaks: attacks (D-100)", () => {
  test("signed out, the page and its drill-down redirect to login", async ({ page }) => {
    for (const path of ["/leaks", "/leaks?from=2026-10-12&to=2026-10-18", "/transactions?lens=all"]) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/login$/);
    }
  });

  test("a forged or expired session the API refuses ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/coach/leaks", () => ({ status: 401, error: { code: "AUTH_002", message: "Token invalid" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/leaks");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("merchant names from the data render as text, not HTML", async ({ signedIn: page, api }) => {
    const hostile = `<img src=x onerror="window.__pwned=1">`;
    const data = cx.leaks();
    data.lenses[0].merchants[0] = { ...data.lenses[0].merchants[0], merchant: hostile };
    api.on("GET /api/v1/coach/leaks", () => ({ data }));
    await page.goto("/leaks?from=2026-10-12&to=2026-10-18");
    await expect(page.getByRole("link", { name: hostile, exact: true })).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
    expect(await page.locator("img[src='x']").count()).toBe(0);
  });

  test("hostile URL parameters are ignored or shown safely", async ({ signedIn: page, api }) => {
    await page.goto(`/leaks?from=${encodeURIComponent("<script>alert(1)</script>")}&to=2026-10-18`);
    await expect(page.getByRole("status").filter({ hasText: "Showing the period instead" })).toBeVisible();
    await page.goto(`/transactions?lens=${encodeURIComponent("../../api/v1/admin")}`);
    await expect(page.getByRole("list", { name: "Active filters" })).toHaveCount(0);
    expect(api.calls.filter((c) => c.path.includes("admin"))).toHaveLength(0);
  });

  test("no tokens or secrets reach the page or storage", async ({ signedIn: page }) => {
    await page.goto("/leaks");
    await expect(page.getByRole("heading", { name: "Where it leaks" })).toBeVisible();
    const html = await page.content();
    expect(html).not.toContain("e2e-session");
    // (The test session cookie is set by the fixture without HttpOnly, so document.cookie isn't checked here.)
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
    expect(stored).not.toContain("e2e-session");
  });
});
