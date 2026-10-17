import type { Page } from "@playwright/test";
import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";

/** The page's own alert: Next's route announcer is also `role="alert"`. */
const alertOf = (page: Page) => page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"));

/**
 * "Ask your money" in a real browser (AOS-711): ask by typing or tapping, read the answer and "How I got this",
 * follow a link to Activity and come back, rate the answer; plus the attacks (D-100): signed out, a session
 * the API refuses, hostile text from the server, nothing secret in the page or storage.
 */
test.describe("Ask your money", () => {
  test("type a question → answer, steps and a link to Activity; back returns to Ask", async ({ signedIn: page, api }) => {
    await page.goto("/ask");
    await expect(page.getByRole("heading", { level: 2, name: "Ask your money", exact: true })).toBeVisible();
    await page.getByRole("textbox", { name: "Your question", exact: true }).fill("Zepto this month vs last month");
    await page.getByRole("button", { name: "Ask", exact: true }).click();
    await expect(page.getByText(fx.askAnswer().answer, { exact: true })).toBeVisible();
    await expect.poll(() => api.callsTo("POST", "/api/v1/ask").map((c) => c.body)).toEqual([{ question: "Zepto this month vs last month" }]);
    await page.getByText("How I got this", { exact: true }).click();
    await expect(page.getByRole("table", { name: "Spending at Zepto", exact: true }).first()).toContainText("₹1,440 · 4 payments");
    await page.getByRole("link", { name: "See these payments in Activity", exact: true }).last().click();
    await expect(page).toHaveURL(/\/transactions\?from=2026-09-01&to=2026-09-30&q=Zepto$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/ask$/);
  });

  test("a suggestion asks straight away, and Enter submits", async ({ signedIn: page, api }) => {
    await page.goto("/ask");
    await page.getByRole("button", { name: "Kitna kharcha hua aaj?", exact: true }).click();
    await expect(page.getByRole("region", { name: /Answer to/ })).toBeVisible();
    await page.getByRole("textbox", { name: "Your question", exact: true }).fill("food vs last month");
    await page.keyboard.press("Enter");
    await expect.poll(() => api.callsTo("POST", "/api/v1/ask").map((c) => (c.body as { question: string }).question)).toEqual(["Kitna kharcha hua aaj?", "food vs last month"]);
  });

  test("thumbs up is sent for that answer and shown pressed", async ({ signedIn: page, api }) => {
    await page.goto("/ask");
    await page.getByRole("button", { name: "Food vs last month", exact: true }).click();
    const group = page.getByRole("group", { name: "Was this helpful?", exact: true });
    await group.getByRole("button", { name: "Yes", exact: true }).click();
    await expect(page.getByText("Thanks, saved.", { exact: true })).toBeVisible();
    await expect(group.getByRole("button", { name: "Yes", exact: true })).toHaveAttribute("aria-pressed", "true");
    await expect.poll(() => api.callsTo("POST", "/api/v1/ask/feedback").map((c) => c.body)).toEqual([{ askId: fx.askAnswer().askId, helpful: true }]);
  });

  test("a templated answer shows its notice; a rate limit explains itself and Try again works", async ({ signedIn: page, api }) => {
    let n = 0;
    api.on("POST /api/v1/ask", () => (++n === 1
      ? { status: 429, error: { code: "RATE_001", message: "Too many questions. Try again in 12 minutes." } }
      : { data: fx.askAnswer({ source: "template", notice: "I couldn't answer that reliably in my own words, so here are the numbers from your data." }) }));
    await page.goto("/ask");
    await page.getByRole("button", { name: "Food vs last month", exact: true }).click();
    await expect(alertOf(page)).toContainText("Too many questions. Try again in 12 minutes.");
    await page.getByRole("button", { name: "Try again", exact: true }).click();
    await expect(page.getByText(/couldn't answer that reliably/)).toBeVisible();
    await expect(page.getByText("Straight from your data", { exact: true })).toBeVisible();
  });

  test("when Ask is off the page says how to turn it on and sends nothing", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/ask", () => ({ data: { enabled: false, maxQuestionLength: 300 } }));
    await page.goto("/ask");
    await expect(page.getByRole("status").filter({ hasText: "AI_ASK_ENABLED" })).toBeVisible();
    await expect(page.getByRole("textbox", { name: "Your question", exact: true })).toBeDisabled();
    expect(api.callsTo("POST", "/api/v1/ask")).toHaveLength(0);
  });

  test("reachable from Home and from More", async ({ signedIn: page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: /^Ask your money/ }).first().click();
    await expect(page).toHaveURL(/\/ask$/);
    await page.goto("/more");
    await page.getByRole("main").getByRole("link", { name: "Ask your money", exact: true }).click();
    await expect(page).toHaveURL(/\/ask$/);
  });

  test("accessible, with the answer and the steps open", async ({ signedIn: page }) => {
    await page.goto("/ask");
    await page.getByRole("button", { name: "Food vs last month", exact: true }).click();
    await page.getByText("How I got this", { exact: true }).click();
    await expect(page.getByRole("table").first()).toBeVisible();
    await expectAccessible(page);
  });
});

test.describe("Ask your money: attacks (D-100)", () => {
  test("signed out → login, and nothing is asked", async ({ page }) => {
    const asks: string[] = [];
    await page.route("**/api/v1/ask**", (r) => { asks.push(r.request().url()); return r.abort(); });
    await page.goto("/ask");
    await expect(page).toHaveURL(/\/login$/);
    expect(asks).toEqual([]);
  });

  test("a session the API refuses (forged or expired) ends at login", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/ask", () => ({ status: 401, error: { code: "AUTH_002", message: "Invalid token" } }))
      .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/ask");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("hostile text in the answer and in merchant names is shown as text, never run", async ({ signedIn: page, api }) => {
    const evil = "<img src=x onerror=\"window.__pwned=1\"> Store";
    api.on("POST /api/v1/ask", () => ({ data: fx.askAnswer({
      answer: "<script>window.__pwned=1</script> you spent ₹60",
      steps: [{ ...fx.askAnswer().steps[0], merchant: evil, result: { spent: null, received: null, payments: null, total: "60.00", rows: [{ label: evil, spent: "60.00", payments: 1, category: "other" }], points: [] } }],
      comparisons: [],
    }) }));
    await page.goto("/ask");
    await page.getByRole("button", { name: "Food vs last month", exact: true }).click();
    await expect(page.getByText("<script>window.__pwned=1</script> you spent ₹60", { exact: true })).toBeVisible();
    await page.getByText("How I got this", { exact: true }).click();
    await expect(page.getByRole("rowheader", { name: evil, exact: true })).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
    await expect(page.locator("main img")).toHaveCount(0);
    const link = page.getByRole("link", { name: "See these payments in Activity", exact: true });
    expect(new URL((await link.getAttribute("href"))!, "https://a.example").searchParams.get("q")).toBe(evil);
  });

  test("no token, key or question is kept in the page, storage or URL", async ({ signedIn: page }) => {
    await page.goto("/ask");
    await page.getByRole("textbox", { name: "Your question", exact: true }).fill("Zepto last month 98000 00012");
    await page.getByRole("button", { name: "Ask", exact: true }).click();
    await expect(page.getByRole("region", { name: /Answer to/ })).toBeVisible();
    expect(page.url()).not.toContain("98000");
    // (The mocked session cookie isn't HttpOnly in this fixture, so document.cookie isn't checked here; the real one is.)
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
    expect(stored).not.toContain("98000");
    expect(stored).not.toContain("e2e-session");
    const html = await page.content();
    expect(html).not.toMatch(/sk-ant-|ANTHROPIC_API_KEY=|e2e-session/);
  });
});
