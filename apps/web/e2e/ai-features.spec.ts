import type { Page } from "@playwright/test";
import { expect, expectAccessible, test } from "./support/fixtures";
import type { MockApi } from "./support/mock-api";
import { fx } from "../test/fixtures";

/**
 * Settings → AI features in a real browser (AOS-704, D-67): the per-feature switch (off by default), the
 * notice of exactly what is sent and where, and the D-100 attack cases (signed out, a session the server
 * refuses, untrusted text, no secrets in the page).
 */

/** The page's own alert: Next's route announcer is also `role="alert"`. */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));
const section = (page: Page) => page.getByRole("region", { name: "AI features" });
const NAME = "Categorise unknown shops";

/** The categoriser set up on the server (cloud); the user's choice is kept as the real API would. */
function aiOn(api: MockApi, over: Parameters<typeof fx.aiFeature>[0] = {}) {
  let feature = fx.aiFeature(over);
  api.on("GET /api/v1/ai/features", () => ({ data: { features: [feature], monthlyCapUsd: "2.00" } }))
    .on("PATCH /api/v1/ai/features/:key", (req) => {
      const body = req.postDataJSON() as { enabled: boolean };
      feature = { ...feature, enabled: body.enabled };
      return { data: feature };
    });
}

test.describe("Settings → AI features", () => {
  test("each feature starts off, with what is sent, what never is, and where it goes", async ({ signedIn: page, api }) => {
    aiOn(api);
    await page.goto("/settings");
    const ai = section(page);
    await expect(ai.getByRole("switch", { name: NAME, exact: true })).toHaveAttribute("aria-checked", "false");
    await expect(ai.getByRole("list", { name: `What ${NAME} sends` })).toContainText("cleaned business name");
    await expect(ai.getByRole("list", { name: `What ${NAME} never sends` })).toContainText("Payments to people");
    await expect(ai).toContainText("Where it goes: Anthropic's Claude API: a cloud service outside India, over HTTPS.");
    await expect(ai).toContainText("capped at $2.00 a month");
    await expectAccessible(page);
  });

  test("turn on, then off: the choice is sent and confirmed", async ({ signedIn: page, api }) => {
    aiOn(api);
    await page.goto("/settings");
    const toggle = section(page).getByRole("switch", { name: NAME, exact: true });
    await toggle.click();
    await expect(section(page).getByRole("status")).toHaveText(`${NAME} is on.`);
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await toggle.click();
    await expect(section(page).getByRole("status")).toHaveText(`${NAME} is off.`);
    expect(api.callsTo("PATCH", "/api/v1/ai/features/categorization").map((c) => c.body)).toEqual([{ enabled: true }, { enabled: false }]);
  });

  test("the keyboard can turn it on", async ({ signedIn: page, api }) => {
    aiOn(api);
    await page.goto("/settings");
    const toggle = section(page).getByRole("switch", { name: NAME, exact: true });
    await toggle.focus();
    await page.keyboard.press("Space");
    await expect(toggle).toHaveAttribute("aria-checked", "true");
  });

  test("a feature the server doesn't run can't be switched on", async ({ signedIn: page }) => {
    await page.goto("/settings"); // default mock: not set up on the server
    await expect(section(page).getByRole("switch", { name: NAME, exact: true })).toBeDisabled();
    await expect(section(page)).toContainText("Not set up on this server yet, so it stays off.");
  });

  test("a change the server refuses shows its reason and keeps the switch off", async ({ signedIn: page, api }) => {
    aiOn(api);
    api.on("PATCH /api/v1/ai/features/:key", () => ({ status: 409, error: { code: "AI_002", message: "categorization isn't set up on this server" } }));
    await page.goto("/settings");
    await section(page).getByRole("switch", { name: NAME, exact: true }).click();
    await expect(alertOf(page)).toContainText("isn't set up on this server");
    await expect(section(page).getByRole("switch", { name: NAME, exact: true })).toHaveAttribute("aria-checked", "false");
  });

  // ── Attacks (D-100) ──

  test("signed out, Settings goes to the login page and asks for nothing", async ({ page, api }) => {
    await page.goto("/settings");
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("GET", "/api/v1/ai/features")).toHaveLength(0);
  });

  test("a session the server refuses (forged or expired) ends at login, and nothing is changed", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/connections", () => ({ status: 401, error: { code: "AUTH_001", message: "Invalid token" } }))
       .on("GET /api/v1/ai/features", () => ({ status: 401, error: { code: "AUTH_001", message: "Invalid token" } }))
       .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/settings");
    await expect(page).toHaveURL(/\/login$/);
    expect(api.callsTo("PATCH", "/api/v1/ai/features/categorization")).toHaveLength(0);
  });

  test("text from the server is rendered as text, never as HTML", async ({ signedIn: page, api }) => {
    aiOn(api, { title: '<img src=x onerror="window.__pwned=1">Shops', sends: ['<script>window.__pwned=1</script>'] });
    await page.goto("/settings");
    const ai = section(page);
    await expect(ai.getByText("<script>window.__pwned=1</script>")).toBeVisible();
    await expect(ai.locator("img")).toHaveCount(0);
    await expect(ai.locator("script")).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
  });

  test("no key, token or secret is in the page or the browser's storage", async ({ signedIn: page, api }) => {
    aiOn(api);
    await page.goto("/settings");
    await expect(section(page).getByRole("switch", { name: NAME, exact: true })).toBeVisible();
    const html = await page.content();
    const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }));
    for (const secret of [/sk-ant-/i, /ANTHROPIC_API_KEY/, /x-api-key/i, /e2e-session/]) {
      expect(html).not.toMatch(secret);
      expect(storage).not.toMatch(secret);
    }
  });
});
