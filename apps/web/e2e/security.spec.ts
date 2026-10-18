import { randomUUID } from "node:crypto";
import { test as base, type Page } from "@playwright/test";
import { E2E_JWT_SECRET } from "../playwright.config";
import { expect, test } from "./support/fixtures";
import { signAccessToken, signIn, tamper } from "./support/session";
import { fx } from "../test/fixtures";

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
  "/documents", "/briefings", "/leaks", "/ask", "/settings", "/settings/security", "/auth/passkey", "/onboarding", "/anomalies/an-1", "/connections/gmail/callback?code=x&state=y",
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
    await page.goto("/auth/callback?code=stranger-code&state=e2e-login-state");
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

test.describe("Settings → Security (mocked)", () => {
  mockedOnly();

  test("lists where you're signed in and signs out everywhere, landing on login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/auth/sessions", () => ({
      data: [
        { id: "s-1", device: "Chrome on Mac", current: true, ipAddress: null, userAgent: null, lastActiveAt: "2026-10-18T05:00:00Z", createdAt: "2026-10-17T05:00:00Z" },
        { id: "s-2", device: "<img src=x onerror=alert(1)>", current: false, ipAddress: null, userAgent: null, lastActiveAt: null, createdAt: "2026-10-16T05:00:00Z" },
      ],
    }));
    api.on("POST /api/v1/auth/sessions/revoke-all", () => ({ data: { revoked: 2, signedOut: true } }));
    await page.goto("/settings/security");
    await expect(page.getByRole("heading", { level: 2, name: "Security" })).toBeVisible();
    await expect(page.getByRole("listitem", { name: "Chrome on Mac" }).getByText("This device")).toBeVisible();
    // Untrusted text renders as text.
    await expect(page.getByText("<img src=x onerror=alert(1)>")).toBeVisible();
    expect(await page.getByRole("list", { name: "Sessions" }).locator("img").count()).toBe(0);

    page.once("dialog", (d) => d.accept());
    await page.getByRole("button", { name: "Sign out everywhere", exact: true }).click();
    await expect.poll(() => api.callsTo("POST", "/api/v1/auth/sessions/revoke-all").length).toBe(1);
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe("Briefings → AI wording (mocked, AOS-716)", () => {
  mockedOnly();

  test("an AI wording renders as text, is marked, and the plain numbers are one click away", async ({ signedIn: page, api }) => {
    const hostile = "<img src=x onerror=alert(1)> You spent ₹18,000 this week.";
    api.on("GET /api/v1/briefings/latest", () => ({ data: fx.briefing({ narration: hostile, narrationModel: "claude-haiku-5-5", narrationStatus: "narrated" }) }));
    await page.goto("/briefings");
    await expect(page.getByText(hostile)).toBeVisible();
    await expect(page.getByText("Worded by AI")).toBeVisible();
    expect(await page.getByTestId("briefing-text").locator("img").count()).toBe(0);
    await page.getByRole("button", { name: "Show the plain numbers" }).click();
    await expect(page.getByTestId("briefing-text")).toHaveText("You spent ₹18,000 this week.");
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
    const saved = await page.request.post("/api/v1/transactions/cash", { data: { clientRef: randomUUID(), amount: "777", category: "shopping", note: "Alice only" }, headers: { origin: baseURL! } });
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
    const attack = await page.request.patch(`/api/v1/transactions/${id}`, { data: { category: "food" }, headers: { origin: baseURL! } });
    const missing = await page.request.patch(`/api/v1/transactions/${randomUUID()}`, { data: { category: "food" }, headers: { origin: baseURL! } });
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

/** Browser hardening headers on every page (AOS-618, AUD-07). */
base.describe("security headers", () => {
  for (const path of ["/login", "/auth/callback", "/sw.js", "/manifest.webmanifest"]) {
    base(`${path} is served with the hardening headers`, async ({ request }) => {
      const res = await request.get(path, { maxRedirects: 0 });
      const h = res.headers();
      expect(h["x-frame-options"]).toBe("DENY");
      expect(h["x-content-type-options"]).toBe("nosniff");
      expect(h["referrer-policy"]).toBe("no-referrer");
      expect(h["cross-origin-opener-policy"]).toBe("same-origin");
      expect(h["permissions-policy"]).toContain("camera=()");
      expect(h["x-powered-by"]).toBeUndefined();
    });
  }

  base("pages have a nonce-based CSP with no inline script allowance, and nothing may frame them", async ({ request }) => {
    const csp = (await request.get("/login")).headers()["content-security-policy"] ?? "";
    const script = csp.split("; ").find((d) => d.startsWith("script-src ")) ?? "";
    expect(script).toMatch(/'nonce-[^']+'/);
    expect(script).not.toContain("unsafe-inline");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("object-src 'none'");
  });

  base("the login page runs under its CSP without a single violation", async ({ page }) => {
    const violations: string[] = [];
    page.on("console", (m) => { if (/Content Security Policy|Refused to (execute|load|apply)/i.test(m.text())) violations.push(m.text()); });
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();
    // Hydrated (React handlers attached): the error text below only renders client-side.
    await page.goto("/login?error=private");
    await expect(page.getByText("This AdminOS is private. Only its owner can sign in.")).toBeVisible();
    expect(violations).toEqual([]);
  });

  base("injected markup can't run script: inline handlers are blocked by the CSP", async ({ page }) => {
    await page.goto("/login");
    await page.evaluate(() => {
      (window as unknown as { __pwned?: boolean }).__pwned = false;
      // What an HTML-injection bug would let an attacker write: an inline event handler.
      const box = document.createElement("div");
      box.innerHTML = '<img alt="" src="data:," onload="window.__pwned = true"><svg onload="window.__pwned = true"></svg>';
      document.body.appendChild(box);
    });
    await expect.poll(() => page.evaluate(() => document.querySelectorAll("img[alt='']").length)).toBeGreaterThan(0);
    // Give the image time to load: the handler must still not have run.
    await page.waitForLoadState("load");
    expect(await page.evaluate(() => (window as unknown as { __pwned?: boolean }).__pwned)).toBe(false);
  });
});

test.describe("API hardening (real API)", () => {
  realApiOnly();

  test("API responses are inert and uncacheable", async ({ page }) => {
    const res = await page.request.get("/api/v1/transactions");
    const h = res.headers();
    expect(h["content-security-policy"]).toContain("default-src 'none'");
    expect(h["x-content-type-options"]).toBe("nosniff");
    expect(h["cache-control"]).toBe("no-store");
    expect(h["access-control-allow-origin"]).toBeUndefined();
  });

  test("a cookie mutation from another site, or with no origin at all, is refused (CSRF)", async ({ page, context, baseURL }) => {
    await signIn(context, baseURL!, signAccessToken().token);
    const body = { data: { clientRef: randomUUID(), amount: "5", category: "food" } };
    const forged: Record<string, string>[] = [{ origin: "https://evil.example" }, { "sec-fetch-site": "cross-site" }, {}];
    for (const headers of forged) {
      const res = await page.request.post("/api/v1/transactions/cash", { ...body, headers });
      expect(res.status(), JSON.stringify(headers)).toBe(403);
      expect((await res.json()).error.code).toBe("CSRF_001");
    }
    expect((await page.request.post("/api/v1/transactions/cash", { ...body, headers: { origin: baseURL! } })).status()).toBe(202);
  });

  test("the app's own fetches pass the CSRF check", async ({ page, context, baseURL }) => {
    await signIn(context, baseURL!, signAccessToken().token);
    await page.goto("/transactions");
    await page.getByRole("button", { name: "Add cash spending" }).click();
    await page.getByLabel("Amount in rupees").fill("40");
    await page.getByRole("button", { name: /Food/ }).click();
    await expect(page.getByRole("status")).toHaveText("₹40 cash saved");
  });

  test("sign-in starts at the API with a state and PKCE, and a forged state never reaches Google", async ({ page }) => {
    await page.route("https://accounts.google.com/**", (r) => r.fulfill({ status: 200, body: "google" }));
    await page.goto("/login");
    await page.getByRole("button", { name: "Continue with Google" }).click();
    await expect(page).toHaveURL(/accounts\.google\.com/);
    const q = new URL(page.url()).searchParams;
    expect(q.get("state")?.length).toBeGreaterThanOrEqual(43);
    expect(q.get("code_challenge_method")).toBe("S256");
    expect(q.get("scope")).toBe("openid email profile");
    expect(q.get("access_type")).toBeNull();

    // An attacker's link back with their own code and state: refused before any call to Google.
    await page.goto(`/auth/callback?code=attacker-code&state=${randomUUID()}`);
    await expect(page).toHaveURL(/\/login\?error=auth_failed$/);
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
