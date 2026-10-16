import { test as base, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { MockApi } from "./mock-api";

/**
 * `signedIn`: a page with a session cookie (any value: the mocked API doesn't check it; the route guard
 * only needs it present) and the mocked API installed. `api` lets the test change or inspect the API.
 */
export const test = base.extend<{ api: MockApi; signedIn: Page }>({
  api: async ({ page }, use) => use(await new MockApi(page).install()),
  signedIn: async ({ page, context, api, baseURL }, use) => {
    void api;
    await context.addCookies([{ name: "access_token", value: "e2e-session", url: baseURL! }]);
    await use(page);
  },
});
export { expect };

/** Is this project a phone (bottom navigation) or a desktop (top navigation)? */
export const isMobile = (page: Page) => (page.viewportSize()?.width ?? 1280) < 640;

/** The visible main navigation for this viewport. */
export const mainNav = (page: Page) => page.getByRole("navigation", { name: "Main navigation" }).locator("visible=true");

/** No serious or critical accessibility violations (WCAG 2.2 A/AA rules). */
export async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  const bad = results.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
  expect(bad.map((v) => `${v.id}: ${v.help} (${v.nodes.length}) → ${v.nodes.slice(0, 2).map((n) => n.target.join(" ")).join(" | ")}`)).toEqual([]);
}
