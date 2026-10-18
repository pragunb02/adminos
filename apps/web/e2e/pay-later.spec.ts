import { expect, test } from "./support/fixtures";

/** Pay-later gaps in Review (AOS-909): visible, plain text, and refused without a session. */
const summary = (provider = "LazyPay") => ({
  data: {
    outstanding: "1200.00", currency: "INR",
    providers: [{
      provider, outstanding: "1200.00", nextDue: "2026-11-03", purchasesLast30Days: "200.00", unpaidBills: [],
      unbilledPurchases: { amount: "0.00", count: 0, transactionIds: [] },
      gaps: [{ billId: "b1", billAmount: "1200.00", dueDate: "2026-11-03", status: "upcoming", recorded: "200.00", unrecorded: "1000.00" }],
    }],
  },
});

test.describe("Pay-later gaps", () => {
  test("Review says how much pay-later spending has no recorded purchases", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/pay-later", () => summary());
    await page.goto("/review");
    const section = page.getByRole("region", { name: "Pay-later spending not in your totals" });
    await expect(section).toContainText("only ₹200 of purchases recorded, so ₹1,000 isn't in your spending");
    await expect.poll(() => api.callsTo("GET", "/api/v1/pay-later").length).toBeGreaterThan(0);
  });

  test("an untrusted provider name renders as text", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/pay-later", () => summary("<img src=x onerror=alert(1)>"));
    await page.goto("/review");
    await expect(page.getByText("<img src=x onerror=alert(1)>")).toBeVisible();
    await expect(page.locator("main img[src='x']")).toHaveCount(0);
  });

  test("signed out, Review goes to sign-in", async ({ page }) => {
    await page.goto("/review");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("a forged or expired session that can't be refreshed ends at sign-in", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/pay-later", () => ({ status: 401, error: { code: "AUTH_001", message: "Token invalid" } }))
      .on("POST /api/v1/auth/refresh", () => ({ status: 401, error: { code: "AUTH_004", message: "Session has been revoked" } }));
    await page.goto("/review");
    await expect(page).toHaveURL(/\/login$/);
  });
});
