import { expect, mainNav, test } from "./support/fixtures";

test.describe("cash quick-add (AOS-204)", () => {
  test("amount → category saves once, with a confirmation", async ({ signedIn: page, api }) => {
    await page.goto("/transactions");
    await page.getByRole("button", { name: "Add cash spending" }).click();
    const sheet = page.getByRole("dialog", { name: "Add cash spending" });
    await expect(sheet.getByLabel("Amount in rupees")).toBeFocused();
    await expect(sheet.getByRole("button", { name: /Food/ })).toBeDisabled();
    await sheet.getByLabel("Amount in rupees").fill("250");
    await sheet.getByLabel("Note").fill("chai");
    await sheet.getByRole("button", { name: /Food/ }).dblclick(); // a double tap must not save twice
    await expect(page.getByRole("status")).toHaveText("₹250 cash saved");
    await expect(sheet).toBeHidden();
    const saves = api.callsTo("POST", "/api/v1/transactions/cash");
    expect(saves.length).toBeLessThanOrEqual(2);
    expect(new Set(saves.map((c) => (c.body as { clientRef: string }).clientRef)).size).toBe(1); // same entry → saved once
    expect(saves[0].body).toMatchObject({ amount: "250", category: "food", note: "chai" });
  });

  test("an error keeps the sheet open; retry succeeds", async ({ signedIn: page, api }) => {
    let n = 0;
    api.on("POST /api/v1/transactions/cash", () => (++n === 1 ? { status: 503, error: { code: "SERVER_001", message: "Try again in a moment" } } : { status: 202, data: { saved: true, duplicate: false } }));
    await page.goto("/");
    await page.getByRole("button", { name: "Add cash spending" }).click();
    await page.getByLabel("Amount in rupees").fill("80");
    await page.getByRole("button", { name: /Transport/ }).click();
    await expect(page.getByRole("dialog").getByRole("alert")).toHaveText("Try again in a moment");
    await page.getByRole("button", { name: /Transport/ }).click();
    await expect(page.getByRole("status")).toHaveText("₹80 cash saved");
  });

  test("Escape and the backdrop close without saving; back still works after", async ({ signedIn: page, api }) => {
    await page.goto("/");
    await mainNav(page).getByRole("link", { name: "Bills" }).click();
    await page.getByRole("button", { name: "Add cash spending" }).click();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toBeHidden();
    await page.getByRole("button", { name: "Add cash spending" }).click();
    await page.mouse.click(5, 5);
    await expect(page.getByRole("dialog")).toBeHidden();
    expect(api.callsTo("POST", "/api/v1/transactions/cash")).toHaveLength(0);
    await page.goBack();
    await expect(page).toHaveURL(/\/$/);
  });
});

test.describe("anomaly review", () => {
  test("home → review → resolve → next → resolve → home; back from review returns home", async ({ signedIn: page, api }) => {
    await page.goto("/");
    await page.getByRole("button", { name: "Review", exact: true }).click();
    await expect(page).toHaveURL(/\/anomalies\/an-1$/);
    await expect(page.getByRole("heading", { name: "Unusually large amount" })).toBeVisible();
    await page.goBack();
    await expect(page.getByRole("heading", { name: "Good morning, Pragun" })).toBeVisible();
    await page.goForward();
    await expect(page.getByText("1 more to review")).toBeVisible();
    await page.getByRole("button", { name: "✓ Confirmed Safe" }).click();
    await expect(page.getByRole("heading", { name: "Odd hours transaction" })).toBeVisible();
    await page.getByRole("button", { name: "✕ Confirmed Fraud" }).click();
    await expect(page).toHaveURL(/\/$/);
    expect(api.calls.filter((c) => c.method === "PATCH").map((c) => (c.body as { status: string }).status)).toEqual(["confirmed_safe", "confirmed_fraud"]);
  });

  test("pick another from the list; ← Back goes home", async ({ signedIn: page }) => {
    await page.goto("/anomalies/an-1");
    await page.getByRole("button", { name: /Odd hours transaction/ }).click();
    await expect(page.getByRole("heading", { name: "Odd hours transaction" })).toBeVisible();
    await page.getByRole("button", { name: "← Back" }).click();
    await expect(page).toHaveURL(/\/$/);
  });
});

test.describe("subscriptions", () => {
  test("tabs and keep/cancel on a flagged one", async ({ signedIn: page, api }) => {
    await page.goto("/subscriptions");
    await expect(page.getByText("₹1,847")).toBeVisible();
    await page.getByRole("button", { name: /Flagged/ }).click();
    await expect(page.getByText("No visits in 45 days")).toBeVisible();
    api.on("PATCH /api/v1/subscriptions/s-9/dismiss", () => ({ data: {} }));
    await page.getByRole("button", { name: "Keep" }).click();
    await expect.poll(() => api.callsTo("PATCH", "/api/v1/subscriptions/s-9/dismiss").length).toBe(1);
    await page.getByRole("button", { name: /Cancelled/ }).click();
    await expect(page.getByText("Netflix")).toBeVisible();
  });
});

test.describe("settings", () => {
  test("a toggle saves after a pause; device token is shown once and can be revoked", async ({ signedIn: page, api, context }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]).catch(() => {});
    let tokens: unknown[] = [];
    api.on("GET /api/v1/device-tokens", () => ({ data: tokens }))
       .on("POST /api/v1/device-tokens", () => { tokens = [{ id: "d1", name: "My Mac – SMS reader", scopes: [], createdAt: "2026-10-16T00:00:00Z", expiresAt: "2027-10-16T00:00:00Z", lastUsedAt: null, revokedAt: null }]; return { data: { ...(tokens[0] as object), token: "dev-secret-123" } }; })
       .on("DELETE /api/v1/device-tokens/d1", () => { tokens = []; return { data: {} }; });
    await page.goto("/settings");
    const alerts = page.getByRole("switch", { name: "Anomaly alerts" });
    await alerts.click();
    await expect(alerts).toHaveAttribute("aria-checked", "false");
    await expect.poll(() => api.callsTo("PATCH", "/api/v1/users/me/notification-preferences").length, { timeout: 3000 }).toBe(1);
    await page.getByRole("button", { name: "Connect device" }).click();
    await expect(page.getByText("dev-secret-123")).toBeVisible();
    await page.getByRole("button", { name: "Done" }).click();
    await expect(page.getByText("dev-secret-123")).toBeHidden();
    page.once("dialog", (d) => d.accept());
    await page.getByRole("button", { name: "Revoke" }).click();
    await expect(page.getByText("No devices connected.")).toBeVisible();
  });
});

test.describe("statement import", () => {
  test("a real file upload; a PDF asks for its password; the result links to transactions", async ({ signedIn: page, api }) => {
    let n = 0;
    api.on("POST /api/v1/statements", () => (++n === 1
      ? { status: 422, error: { code: "PASSWORD_REQUIRED", message: "Password required" } }
      : { data: { bankCode: "hdfc", accountLast4: "1234", periodStart: "2026-09-01", periodEnd: "2026-09-30", rows: 40, accepted: 37, duplicates: 2, skippedRows: 5, warnings: [] } }));
    await page.goto("/import");
    await page.getByLabel("Statement file").setInputFiles({ name: "Sept.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.7") });
    await page.getByRole("button", { name: "Import" }).click();
    await expect(page.locator("form").getByRole("alert")).toContainText("password-protected");
    await page.getByLabel("Statement password").fill("01011990");
    await page.getByRole("button", { name: "Import" }).click();
    await expect(page.getByRole("status")).toContainText("37 new transactions imported");
    await page.getByRole("link", { name: "See transactions →" }).click();
    await expect(page).toHaveURL(/\/transactions$/);
    await page.goBack();
    await expect(page.getByRole("heading", { name: "Import a bank statement" })).toBeVisible();
  });
});

test.describe("transactions feed", () => {
  test("filters reach the API; load more appends", async ({ signedIn: page, api }) => {
    await page.goto("/transactions");
    await expect(page.getByText("Swiggy")).toBeVisible();
    await page.getByRole("button", { name: "Load more" }).click();
    await expect(page.getByText("Zepto")).toBeVisible();
    await expect(page.getByRole("button", { name: "Load more" })).toBeHidden();
    await page.getByLabel("Category").selectOption("food");
    await page.getByLabel("Search merchant").fill("swig");
    await expect.poll(() => api.calls.filter((c) => c.path === "/api/v1/transactions").length).toBeGreaterThanOrEqual(4);
    await expect(page.getByText("Swiggy")).toBeVisible();
  });

  test("server errors retry by themselves", async ({ signedIn: page, api }) => {
    let n = 0;
    api.on("GET /api/v1/transactions", () => (++n < 3 ? { status: 503, error: { code: "SERVER_001", message: "Busy" } } : { data: [] }));
    await page.goto("/transactions");
    await expect(page.getByText("No transactions found")).toBeVisible({ timeout: 10_000 });
    expect(n).toBe(3);
  });

  test("an error can be retried", async ({ signedIn: page, api }) => {
    let n = 0;
    // Server errors are retried automatically first; a client error is shown straight away.
    api.on("GET /api/v1/transactions", () => (++n === 1 ? { status: 400, error: { code: "VALIDATION_003", message: "Invalid 'from'" } } : { data: [] }));
    await page.goto("/transactions");
    await expect(page.getByText("Invalid 'from'")).toBeVisible();
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("No transactions found")).toBeVisible();
  });
});

test.describe("login and onboarding", () => {
  test("login sends you to Google with this app's callback", async ({ page }) => {
    await page.route("https://accounts.google.com/**", (r) => r.fulfill({ status: 200, body: "google" }));
    await page.goto("/login");
    await page.getByRole("button", { name: "Continue with Google" }).click();
    await expect(page).toHaveURL(/accounts\.google\.com\/o\/oauth2\/v2\/auth\?client_id=e2e-client-id/);
    expect(new URL(page.url()).searchParams.get("redirect_uri")).toMatch(/\/auth\/callback$/);
  });

  test("a failed sign-in comes back to login with a message", async ({ page }) => {
    await page.goto("/auth/callback?error=access_denied");
    await expect(page).toHaveURL(/\/login\?error=auth_failed$/);
    await expect(page.getByText("Authentication failed. Please try again.")).toBeVisible();
  });

  test("onboarding: skip both → dashboard; back returns to onboarding start", async ({ signedIn: page }) => {
    await page.goto("/onboarding");
    await page.getByRole("button", { name: "Get started" }).click();
    await page.getByRole("button", { name: "Skip for now" }).click();
    await page.getByRole("button", { name: "Skip for now" }).click();
    await expect(page).toHaveURL(/\/$/);
    await page.goBack();
    await expect(page).not.toHaveURL(/\/onboarding/); // replace(): onboarding isn't a back target once finished
  });
});
