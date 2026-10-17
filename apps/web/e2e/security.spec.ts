import { randomUUID } from "node:crypto";
import { test as base, type Page } from "@playwright/test";
import { E2E_JWT_SECRET } from "../playwright.config";
import { expect, test } from "./support/fixtures";
import { signAccessToken, signIn, tamper } from "./support/session";

/**
 * Attack specs (AOS-619, D-91, D-100 §4a). The reviewer runs these before every merge.
 *
 * Extend, don't fork: a new screen adds its path to PAGES (signed-out redirect), its untrusted text to the
 * rendering checks, and any new token-like value to SECRET_PATTERNS. Real-API attacks run only in the
 * `full-stack` project; mocked ones only in the others.
 */

/** Every page of the app. A new page must be added here. */
export const PAGES = [
  "/", "/spending", "/spending/category/food", "/transactions", "/review", "/more", "/import", "/bills", "/subscriptions",
  "/documents", "/briefings", "/settings", "/onboarding", "/anomalies/an-1", "/connections/gmail/callback?code=x&state=y",
];

/** Paths that only *look* like public pages (AUD-13: the guard used to prefix-match). */
const LOOK_ALIKES = ["/login-admin", "/loginx/transactions", "/healthz", "/auth/callbackx", "/auth/callback/extra"];

/**
 * Secret *values* that must never be readable by page JavaScript or shipped in a bundle. (Setting names such as
 * GOOGLE_CLIENT_SECRET legitimately appear in help text; only values matter.)
 */
export const SECRET_PATTERNS: RegExp[] = [
  /eyJ[\w-]{5,}\.eyJ[\w-]{5,}\.[\w-]*/, // any JWT
  /-----BEGIN [A-Z ]*PRIVATE KEY-----/,
  /GOCSPX-[\w-]{10,}/, // a Google OAuth client secret
  /sk-ant-[\w-]{10,}/, // an Anthropic API key
  /AKIA[0-9A-Z]{16}/, // an AWS-style access key id (R2 keys use the same S3 API)
];

const isFullStack = (name: string) => name === "full-stack";
// Playwright reads fixtures from an object pattern, so the empty `{}` is required.
const realApiOnly = () => test.beforeEach(({}, info) => test.skip(!isFullStack(info.project.name), "needs the real API (full-stack project)"));
const mockedOnly = () => test.beforeEach(({}, info) => test.skip(isFullStack(info.project.name), "mocked API projects only"));

base.describe("signed out", () => {
  for (const path of [...PAGES, ...LOOK_ALIKES]) {
    base(`${path} redirects to the login page`, async ({ page }) => {
      await page.goto(path);
      await expect(page).toHaveURL(/\/login$/);
      await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();
    });
  }

  base("an empty session cookie is no session", async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: "access_token", value: "", url: baseURL! }]);
    await page.goto("/transactions");
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe("sign-in by anyone but the owner (mocked)", () => {
  mockedOnly();

  test("a refused Google account sees that this AdminOS is private, and nothing is shown", async ({ page, api }) => {
    api.on("POST /api/v1/auth/google", () => ({ status: 403, error: { code: "USER_003", message: "This AdminOS is private" } }));
    await page.goto("/auth/callback?code=stranger-code");
    await expect(page).toHaveURL(/\/login\?error=private$/);
    await expect(page.getByText("This AdminOS is private. Only its owner can sign in.")).toBeVisible();
    await expect.poll(() => api.callsTo("POST", "/api/v1/auth/google").length).toBe(1);
    // No data call was made for the stranger.
    expect(api.calls.filter((c) => !c.path.startsWith("/api/v1/auth/"))).toEqual([]);
  });

  test("Google's own refusal never reaches the API", async ({ page, api }) => {
    await page.goto("/auth/callback?error=access_denied");
    await expect(page).toHaveURL(/\/login\?error=auth_failed$/);
    expect(api.callsTo("POST", "/api/v1/auth/google")).toHaveLength(0);
  });
});

test.describe("forged and stale sessions (real API)", () => {
  realApiOnly();

  const forged: Record<string, () => string> = {
    "signed with another secret": () => signAccessToken(undefined, { secret: `attacker-${randomUUID()}-${randomUUID()}` }).token,
    "expired": () => signAccessToken(undefined, { expiresInSec: -60 }).token,
    "alg none": () => signAccessToken(undefined, { alg: "none" }).token,
    "a device token used as a login": () => signAccessToken(undefined, { typ: "device" }).token,
    "from another issuer": () => signAccessToken(undefined, { iss: "someone-else" }).token,
    "tampered payload": () => {
      const { token } = signAccessToken();
      const now = Math.floor(Date.now() / 1000);
      return tamper(token, { sub: randomUUID(), typ: "access", email: "x@e2e.test", role: "owner", iss: "adminos", iat: now, exp: now + 600 });
    },
    "garbage": () => "not-a-jwt",
  };

  for (const [name, make] of Object.entries(forged)) {
    test(`a token ${name} is refused and the user lands on login`, async ({ page, context, baseURL }) => {
      await signIn(context, baseURL!, make());
      expect((await page.request.get("/api/v1/analytics/summary")).status()).toBe(401);
      await page.goto("/transactions");
      await expect(page).toHaveURL(/\/login$/);
    });
  }

  test("another user's transaction is not found, and stays as it was", async ({ page, context, baseURL }) => {
    const alice = signAccessToken();
    await signIn(context, baseURL!, alice.token);
    const saved = await page.request.post("/api/v1/transactions/cash", { data: { clientRef: randomUUID(), amount: "777", category: "shopping", note: "Alice only" } });
    expect(saved.status()).toBe(202);
    // The pipeline is asynchronous: wait until Alice's entry exists.
    let id = "";
    await expect.poll(async () => {
      const list = await (await page.request.get("/api/v1/transactions")).json();
      id = list.data?.[0]?.id ?? "";
      return id;
    }, { timeout: 20_000 }).not.toBe("");

    const bob = signAccessToken();
    await context.clearCookies();
    await signIn(context, baseURL!, bob.token);
    const attack = await page.request.patch(`/api/v1/transactions/${id}`, { data: { category: "food" } });
    const missing = await page.request.patch(`/api/v1/transactions/${randomUUID()}`, { data: { category: "food" } });
    expect(attack.status()).toBe(404);
    expect((await attack.json()).error.code).toBe((await missing.json()).error.code);
    expect(JSON.stringify(await (await page.request.get("/api/v1/transactions")).json())).not.toContain(id);

    await context.clearCookies();
    await signIn(context, baseURL!, alice.token);
    const after = await (await page.request.get("/api/v1/transactions")).json();
    expect(after.data.find((t: { id: string }) => t.id === id)?.category).toBe("shopping");
  });

  test("no secret or token is readable by the page or shipped in its scripts", async ({ page, context, baseURL }) => {
    const { token } = signAccessToken();
    await signIn(context, baseURL!, token);
    for (const path of ["/", "/settings", "/documents"]) {
      await page.goto(path);
      await expect(page.getByRole("main")).toBeVisible();
      await expectNoSecrets(page, token);
    }
  });
});

async function expectNoSecrets(page: Page, token: string) {
  const exposed = await page.evaluate(() => ({
    cookie: document.cookie,
    local: JSON.stringify({ ...localStorage }),
    session: JSON.stringify({ ...sessionStorage }),
    html: document.documentElement.outerHTML,
    scripts: [...document.querySelectorAll("script[src]")].map((s) => (s as HTMLScriptElement).src),
  }));
  // The session cookie is HttpOnly: page script can't see it.
  expect(exposed.cookie).not.toContain("access_token");
  for (const where of [exposed.local, exposed.session, exposed.html]) {
    expect(where).not.toContain(token);
    expect(where).not.toContain(E2E_JWT_SECRET);
    for (const p of SECRET_PATTERNS) expect(where).not.toMatch(p);
  }
  for (const src of exposed.scripts.filter((s) => new URL(s).origin === new URL(page.url()).origin)) {
    const js = await (await page.request.get(src)).text();
    expect(js.includes(E2E_JWT_SECRET), `${src} contains the signing secret`).toBe(false);
    for (const p of SECRET_PATTERNS) expect(p.test(js), `${src} matches ${p}`).toBe(false);
  }
}
