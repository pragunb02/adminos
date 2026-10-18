import { fx } from "../test/fixtures";
import { expect, isMobile, mainNav, test } from "./support/fixtures";
import type { Page } from "@playwright/test";

/** The 5 destinations (D-49) and what proves we're on each. */
const MAIN: { tab: "Home" | "Spending" | "Activity" | "Review" | "More"; path: string; heading: string | RegExp }[] = [
  { tab: "Home", path: "/", heading: /^[A-Z][a-z]+ \d{4}$/ }, // the period label, e.g. "October 2026"
  { tab: "Spending", path: "/spending", heading: /^[A-Z][a-z]+ \d{4}$/ }, // the period label
  { tab: "Activity", path: "/transactions", heading: "Transactions" },
  { tab: "Review", path: "/review", heading: "Review" },
  { tab: "More", path: "/more", heading: "More" },
];
/** Under More (a list on phones, expanded in the desktop rail). */
const SECONDARY = [
  { label: "Where it leaks", path: "/leaks", heading: "Where it leaks" },
  { label: "Wealth", path: "/wealth", heading: "Wealth" },
  { label: "Ask your money", path: "/ask", heading: "Ask your money" },
  { label: "Import statement", path: "/import", heading: "Import a bank statement" },
  { label: "Bills", path: "/bills", heading: "Dues & renewals" },
  { label: "Subscriptions", path: "/subscriptions", heading: "Subscriptions" },
  { label: "Documents", path: "/documents", heading: "Documents" },
  { label: "Briefings", path: "/briefings", heading: "Briefings" },
  { label: "Trips", path: "/trips", heading: "Trips" },
  { label: "Settings", path: "/settings", heading: "Settings" },
] as const;
type Tab = (typeof MAIN)[number]["tab"];

const urlFor = (path: string) => new RegExp(`${path === "/" ? "/" : path}(\\?.*)?$`);

async function expectOn(page: Page, tab: Tab) {
  const p = MAIN.find((x) => x.tab === tab)!;
  await expect(page).toHaveURL(urlFor(p.path));
  await expect(page.getByRole("heading", { level: 2, name: p.heading })).toBeVisible();
  await expect(mainNav(page).getByRole("link", { name: new RegExp(`^${tab}`) })).toHaveAttribute("aria-current", "page");
}

async function openSecondary(page: Page, label: string) {
  if (isMobile(page)) {
    await mainNav(page).getByRole("link", { name: /^More/ }).click();
    await page.getByRole("main").getByRole("link", { name: new RegExp(label) }).click();
  } else {
    await mainNav(page).getByRole("link", { name: label }).click();
  }
}

test.describe("deep links and refresh", () => {
  for (const p of [...MAIN, ...SECONDARY]) {
    test(`${p.path} opens directly and survives a refresh`, async ({ signedIn: page }) => {
      await page.goto(p.path);
      await expect(page.getByRole("heading", { level: 2, name: p.heading })).toBeVisible();
      await page.reload();
      await expect(page.getByRole("heading", { level: 2, name: p.heading })).toBeVisible();
    });
  }
});

test.describe("navigation with back and forward", () => {
  test("Home → Activity → Review → back → back → forward → Spending → back → back → forward → forward", async ({ signedIn: page }) => {
    const nav = mainNav(page);
    const tab = (t: Tab) => nav.getByRole("link", { name: new RegExp(`^${t}`) }).click();
    await page.goto("/");
    await expectOn(page, "Home");
    await tab("Activity");
    await expectOn(page, "Activity");
    await tab("Review");
    await expectOn(page, "Review");
    await page.goBack();
    await expectOn(page, "Activity");
    await page.goBack();
    await expectOn(page, "Home");
    await page.goForward();
    await expectOn(page, "Activity");
    await tab("Spending"); // a new branch drops the old forward history
    await expectOn(page, "Spending");
    await page.goBack();
    await expectOn(page, "Activity");
    await page.goBack();
    await expectOn(page, "Home");
    await page.goForward();
    await expectOn(page, "Activity");
    await page.goForward();
    await expectOn(page, "Spending");
  });

  test("every tab in order, then all the way back", async ({ signedIn: page }) => {
    // Desktop has no More tab: its pages sit directly in the rail.
    const tabs = MAIN.filter((p) => isMobile(page) || p.tab !== "More");
    await page.goto("/");
    for (const p of tabs.slice(1)) {
      await mainNav(page).getByRole("link", { name: new RegExp(`^${p.tab}`) }).click();
      await expectOn(page, p.tab);
    }
    for (const p of [...tabs].reverse().slice(1)) {
      await page.goBack();
      await expectOn(page, p.tab);
    }
  });

  test("secondary pages: open, More stays the current destination, back returns", async ({ signedIn: page }) => {
    await page.goto("/");
    for (const s of SECONDARY) {
      await openSecondary(page, s.label);
      await expect(page).toHaveURL(urlFor(s.path));
      await expect(page.getByRole("heading", { level: 2, name: s.heading })).toBeVisible();
      if (isMobile(page)) await expect(mainNav(page).getByRole("link", { name: /^More/ })).toHaveAttribute("aria-current", "page");
      else await expect(mainNav(page).getByRole("link", { name: s.label })).toHaveAttribute("aria-current", "page");
      await page.goBack();
      if (isMobile(page)) await page.goBack(); // phones went through /more
      await expectOn(page, "Home");
    }
  });

  test("clicking the current tab doesn't add history", async ({ signedIn: page }) => {
    await page.goto("/");
    await mainNav(page).getByRole("link", { name: /^Review/ }).click();
    await expectOn(page, "Review");
    await mainNav(page).getByRole("link", { name: /^Review/ }).click();
    await page.goBack();
    await expectOn(page, "Home");
  });

  test("phones get 5 tabs pinned to the bottom, all within the screen; desktop gets the rail", async ({ signedIn: page }) => {
    await page.goto("/");
    const nav = mainNav(page);
    await expect(nav).toHaveCount(1);
    const width = page.viewportSize()!.width;
    if (isMobile(page)) {
      const box = (await nav.boundingBox())!;
      expect(box.y + box.height).toBeGreaterThan(page.viewportSize()!.height - 40);
      const tabs = nav.getByRole("link");
      await expect(tabs).toHaveCount(5);
      for (let i = 0; i < 5; i++) {
        const b = (await tabs.nth(i).boundingBox())!;
        expect(b.x).toBeGreaterThanOrEqual(0);
        expect(b.x + b.width).toBeLessThanOrEqual(width + 1);
        expect(b.height).toBeGreaterThanOrEqual(44); // touch target
      }
    } else {
      const box = (await nav.boundingBox())!;
      expect(box.x).toBe(0);
      await expect(nav.getByRole("button", { name: "Log out" })).toBeVisible();
    }
  });

  test("the Review tab shows how many suggestions wait", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/transactions/suggestions", () => ({ data: [{}, {}, {}] }));
    await page.goto("/");
    await expect(mainNav(page).getByLabel("3 to review")).toBeVisible();
  });
});

test.describe("route guard and session", () => {
  test("no session → login, for every app page", async ({ page }) => {
    for (const p of [...MAIN, ...SECONDARY]) {
      await page.goto(p.path);
      await expect(page).toHaveURL(/\/login$/);
    }
  });

  test("an expired session that can't be refreshed ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/analytics/summary", () => ({ status: 401, error: { code: "AUTH_001", message: "Token expired" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("an expired session that refreshes keeps you where you were", async ({ signedIn: page, api }) => {
    let first = true;
    api.on("GET /api/v1/dues", () => (first ? ((first = false), { status: 401, error: { code: "AUTH_001", message: "Token expired" } }) : { data: fx.duesMonth({ items: [] }) }));
    await page.goto("/bills");
    await expect(page.getByText("Nothing due in October 2026.")).toBeVisible();
  });

  test("log out (More on phones, the rail on desktop) ends the session", async ({ signedIn: page, api }) => {
    await page.goto(isMobile(page) ? "/more" : "/");
    const scope = isMobile(page) ? page.getByRole("main") : mainNav(page);
    await scope.getByRole("button", { name: "Log out" }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("POST", "/api/v1/auth/logout")).toHaveLength(1);
  });
});
