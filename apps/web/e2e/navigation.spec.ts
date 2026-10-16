import { expect, isMobile, mainNav, test } from "./support/fixtures";

/** Where each tab goes and what proves we're there. */
const PAGES = [
  { tab: "Home", path: "/", heading: "Good morning, Pragun" },
  { tab: "Transactions", path: "/transactions", heading: "Transactions" },
  { tab: "Import", path: "/import", heading: "Import a bank statement" },
  { tab: "Subscriptions", path: "/subscriptions", heading: "Subscriptions" },
  { tab: "Bills", path: "/bills", heading: "Upcoming Bills" },
  { tab: "Briefings", path: "/briefings", heading: "Briefings" },
  { tab: "Settings", path: "/settings", heading: "Settings" },
] as const;
type Tab = (typeof PAGES)[number]["tab"];

async function expectOn(page: import("@playwright/test").Page, tab: Tab) {
  const p = PAGES.find((x) => x.tab === tab)!;
  await expect(page).toHaveURL(new RegExp(`${p.path === "/" ? "/$" : p.path + "$"}`));
  await expect(page.getByRole("heading", { level: 2, name: p.heading })).toBeVisible();
  await expect(mainNav(page).getByRole("link", { name: tab })).toHaveAttribute("aria-current", "page");
}

test.describe("deep links and refresh", () => {
  for (const p of PAGES) {
    test(`${p.path} opens directly and survives a refresh`, async ({ signedIn: page }) => {
      await page.goto(p.path);
      await expectOn(page, p.tab);
      await page.reload();
      await expectOn(page, p.tab);
    });
  }
});

test.describe("tabs with back and forward", () => {
  test("Home → Transactions → Bills → back → back → forward → Settings → back → back → forward → forward", async ({ signedIn: page }) => {
    const nav = mainNav(page);
    await page.goto("/");
    await expectOn(page, "Home");
    await nav.getByRole("link", { name: "Transactions" }).click();
    await expectOn(page, "Transactions");
    await nav.getByRole("link", { name: "Bills" }).click();
    await expectOn(page, "Bills");
    await page.goBack();
    await expectOn(page, "Transactions");
    await page.goBack();
    await expectOn(page, "Home");
    await page.goForward();
    await expectOn(page, "Transactions");
    await nav.getByRole("link", { name: "Settings" }).click(); // a new branch drops the old forward history
    await expectOn(page, "Settings");
    await page.goBack();
    await expectOn(page, "Transactions");
    await page.goBack();
    await expectOn(page, "Home");
    await page.goForward();
    await expectOn(page, "Transactions");
    await page.goForward();
    await expectOn(page, "Settings");
  });

  test("every tab, in order, then all the way back", async ({ signedIn: page }) => {
    await page.goto("/");
    for (const p of PAGES.slice(1)) {
      await mainNav(page).getByRole("link", { name: p.tab }).click();
      await expectOn(page, p.tab);
    }
    for (const p of [...PAGES].reverse().slice(1)) {
      await page.goBack();
      await expectOn(page, p.tab);
    }
  });

  test("clicking the current tab doesn't add history", async ({ signedIn: page }) => {
    await page.goto("/");
    await mainNav(page).getByRole("link", { name: "Bills" }).click();
    await expectOn(page, "Bills");
    await mainNav(page).getByRole("link", { name: "Bills" }).click();
    await page.goBack();
    await expectOn(page, "Home");
  });

  test("every navigation item can be reached and tapped on this screen size", async ({ signedIn: page }) => {
    await page.goto("/");
    const items = mainNav(page).locator("a, button");
    expect(await items.count()).toBe(8);
    for (let i = 0; i < 8; i++) {
      await items.nth(i).scrollIntoViewIfNeeded();
      const box = (await items.nth(i).boundingBox())!;
      const width = page.viewportSize()!.width;
      expect(box.x, await items.nth(i).getAttribute("aria-label") ?? "").toBeGreaterThanOrEqual(0);
      expect(box.x + box.width).toBeLessThanOrEqual(width + 1);
    }
  });

  test("the right navigation shows for the screen size", async ({ signedIn: page }) => {
    await page.goto("/");
    const nav = mainNav(page);
    await expect(nav).toHaveCount(1);
    const box = (await nav.boundingBox())!;
    const height = page.viewportSize()!.height;
    if (isMobile(page)) expect(box.y + box.height).toBeGreaterThan(height - 5); // pinned to the bottom
    else expect(box.y).toBeLessThan(200); // under the header
  });
});

test.describe("route guard and session", () => {
  test("no session → login, for every app page", async ({ page }) => {
    for (const p of PAGES) {
      await page.goto(p.path);
      await expect(page).toHaveURL(/\/login$/);
    }
    await expect(page.getByRole("button", { name: "Continue with Google" })).toBeEnabled();
  });

  test("an expired session that can't be refreshed ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/dashboard/home", () => ({ status: 401, error: { code: "AUTH_001", message: "Token expired" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("an expired session that refreshes keeps you where you were", async ({ signedIn: page, api }) => {
    let first = true;
    api.on("GET /api/v1/bills/upcoming", () => (first ? ((first = false), { status: 401, error: { code: "AUTH_001", message: "Token expired" } }) : { data: [] }));
    await page.goto("/bills");
    await expect(page.getByText("No upcoming bills")).toBeVisible();
    expect(api.callsTo("POST", "/api/v1/auth/refresh")).toHaveLength(1);
  });

  test("log out revokes the session and goes to login", async ({ signedIn: page, api }) => {
    await page.goto("/settings");
    await mainNav(page).getByRole("button", { name: "Log out" }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("POST", "/api/v1/auth/logout")).toHaveLength(1);
  });
});
