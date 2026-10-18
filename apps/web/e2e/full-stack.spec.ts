import { expect, test } from "@playwright/test";
import { signAccessToken, signIn } from "./support/session";

/** Real browser → real Next proxy → real API (in-memory) — AOS-311 full-stack mode. */
test.describe("full stack", () => {
  test("a signed-in user adds cash and sees it in the feed and in the month's total", async ({ page, context, baseURL }) => {
    const { token } = signAccessToken();
    await signIn(context, baseURL!, token);
    await page.goto("/transactions");
    await expect(page.getByText("No transactions yet.")).toBeVisible();

    await page.getByRole("button", { name: "Add cash spending" }).click();
    await page.getByLabel("Amount in rupees").fill("250");
    await page.getByLabel("Note", { exact: true }).fill("chai");
    await page.getByRole("button", { name: /Food/ }).click();
    await expect(page.getByRole("status")).toHaveText("₹250 cash saved");

    // The pipeline is asynchronous (seconds): reload until the entry is there.
    await expect(async () => {
      await page.reload();
      await expect(page.getByText("Chai")).toBeVisible({ timeout: 1000 });
    }).toPass({ timeout: 20_000 });
    await expect(page.getByText("−₹250")).toBeVisible();

    const summary = await page.request.get("/api/v1/analytics/summary");
    expect(summary.ok()).toBe(true);
    expect((await summary.json()).data.totals.spent).toBe("250.00");
  });

  test("one user's data is invisible to another", async ({ page, context, baseURL }) => {
    const a = signAccessToken();
    await signIn(context, baseURL!, a.token);
    // Like the browser's own fetch, send our Origin: the API refuses cookie mutations without it (AOS-618).
    const saved = await page.request.post("/api/v1/transactions/cash", { data: { clientRef: crypto.randomUUID(), amount: "99", category: "shopping" }, headers: { origin: baseURL! } });
    expect(saved.status()).toBe(202);

    const b = signAccessToken();
    await context.clearCookies();
    await signIn(context, baseURL!, b.token);
    await page.goto("/transactions");
    await expect(page.getByText("No transactions yet.")).toBeVisible();
  });

  test("a token signed with the wrong secret is rejected — even one sharing its first 32 bytes (AOS-607) — and the user lands on login", async ({ page, context, baseURL }) => {
    const { token } = signAccessToken(undefined, { secret: "e2e-only-secret-not-for-production-attacker" });
    await signIn(context, baseURL!, token);
    const res = await page.request.get("/api/v1/analytics/summary");
    expect(res.status()).toBe(401);
    await page.goto("/transactions");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("an expired token is rejected", async ({ page, context, baseURL }) => {
    const { token } = signAccessToken(undefined, { expiresInSec: -60 });
    await signIn(context, baseURL!, token);
    expect((await page.request.get("/api/v1/analytics/summary")).status()).toBe(401);
  });

  test("bad input reaches the user as the API's message", async ({ page, context, baseURL }) => {
    const { token } = signAccessToken();
    await signIn(context, baseURL!, token);
    const res = await page.request.get("/api/v1/analytics/series?from=2026-10-10&to=2026-10-01");
    expect(res.status()).toBe(400);
    expect((await res.json()).error.message).toMatch(/^to:/);
  });

  test("messages read by AI (AOS-705) are owner-only: a forged token is refused and an unknown id is not found", async ({ page, context, baseURL }) => {
    const forged = signAccessToken(undefined, { secret: "e2e-only-secret-not-for-production-attacker" });
    await signIn(context, baseURL!, forged.token);
    // Mutations carry our Origin like the browser's own fetch, so they reach the auth check (AOS-618 refuses them first otherwise).
    expect((await page.request.get("/api/v1/review/messages")).status()).toBe(401);
    expect((await page.request.post(`/api/v1/review/messages/${crypto.randomUUID()}/confirm`, { data: {}, headers: { origin: baseURL! } })).status()).toBe(401);

    await context.clearCookies();
    const { token } = signAccessToken();
    await signIn(context, baseURL!, token);
    const list = await page.request.get("/api/v1/review/messages");
    expect(list.status()).toBe(200);
    expect((await list.json()).data).toEqual([]);
    expect((await page.request.post(`/api/v1/review/messages/${crypto.randomUUID()}/confirm`, { data: {}, headers: { origin: baseURL! } })).status()).toBe(404);
    expect((await page.request.post("/api/v1/review/messages/not-an-id/dismiss", { data: {}, headers: { origin: baseURL! } })).status()).toBe(400);
  });

  test("formats to learn (AOS-706) are owner-only too", async ({ page, context, baseURL }) => {
    const forged = signAccessToken(undefined, { secret: "e2e-only-secret-not-for-production-attacker" });
    await signIn(context, baseURL!, forged.token);
    expect((await page.request.get("/api/v1/review/templates")).status()).toBe(401);
    await context.clearCookies();
    const { token } = signAccessToken();
    await signIn(context, baseURL!, token);
    expect((await (await page.request.get("/api/v1/review/templates")).json()).data).toEqual([]);
    expect((await page.request.post(`/api/v1/review/templates/${crypto.randomUUID()}/approve`, { data: {}, headers: { origin: baseURL! } })).status()).toBe(404);
  });
});
