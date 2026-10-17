import { expect, test } from "./support/fixtures";

/** Dues & renewals in a real browser (AOS-503): the month lives in the URL. */
test.describe("Dues & renewals", () => {
  test("shows this month's dues; next → next → back → forward → refresh keep the month", async ({ signedIn: page, api }) => {
    await page.goto("/bills");
    await expect(page.getByRole("heading", { name: "Dues & renewals" })).toBeVisible();
    await expect(page.getByLabel("Totals")).toContainText("₹20,800");
    await expect(page.getByRole("region", { name: "Sat, 17 Oct" })).toContainText("Due tomorrow");

    await page.getByRole("button", { name: "Next month" }).click();
    await expect(page).toHaveURL(/\/bills\?m=2026-11$/);
    await expect(page.getByText("Nothing due in November 2026.")).toBeVisible();
    await page.getByRole("button", { name: "Next month" }).click();
    await expect(page).toHaveURL(/\?m=2026-12$/);
    await page.goBack();
    await expect(page).toHaveURL(/\?m=2026-11$/);
    await page.goForward();
    await expect(page).toHaveURL(/\?m=2026-12$/);
    await page.reload();
    await expect(page.getByText("Nothing due in December 2026.")).toBeVisible();
    expect(api.calls.some((c) => c.path === "/api/v1/dues")).toBe(true);

    await page.getByRole("button", { name: "Back to this month" }).click();
    await expect(page).toHaveURL(/\/bills$/);
    await expect(page.getByLabel("Totals")).toContainText("₹20,800");
  });
});

test.describe("AutoPay & mandates", () => {
  test("Subscriptions lists mandates and flags an unused one", async ({ signedIn: page }) => {
    await page.goto("/subscriptions");
    const section = page.getByRole("region", { name: "AutoPay & mandates" });
    await expect(section.getByRole("listitem", { name: "BAJAJ FINANCE LTD" })).toContainText("e-NACH · monthly · up to ₹5,400");
    await expect(section.getByRole("listitem", { name: "Cult.fit" })).toContainText("still needed?");
  });
});
