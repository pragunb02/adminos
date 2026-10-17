import { expect, test } from "./support/fixtures";

/** Activity in a real browser (AOS-307): filters in the URL, batch apply, edit sheet with Back, undo. */
const visible = (l: import("@playwright/test").Locator) => l.locator("visible=true");

test.describe("Activity", () => {
  test("filters apply together into the URL; chips remove them; back/forward/refresh restore them", async ({ signedIn: page, api, isMobile }) => {
    await page.goto("/transactions");
    await expect(page.getByRole("button", { name: /^Edit Swiggy/ })).toBeVisible();
    if (isMobile) await page.getByRole("button", { name: "Filters" }).click();
    await visible(page.getByLabel("Category")).selectOption("food");
    await visible(page.getByLabel("Kind")).selectOption("spend");
    await expect(page).toHaveURL(/\/transactions$/); // nothing applied while choosing
    await visible(page.getByRole("button", { name: "Apply" })).click();
    await expect(page).toHaveURL(/\/transactions\?category=food&nature=spend$/);
    await expect.poll(() => api.calls.some((c) => c.path === "/api/v1/transactions")).toBe(true);
    await page.getByRole("button", { name: "Remove filter Spending" }).click();
    await expect(page).toHaveURL(/\/transactions\?category=food$/);
    await page.goBack();
    await expect(page).toHaveURL(/category=food&nature=spend$/);
    await page.reload();
    await expect(page.getByRole("list", { name: "Active filters" })).toContainText("Food ×");
    await page.goBack();
    await expect(page).toHaveURL(/\/transactions$/);
    await page.goForward();
    await expect(page).toHaveURL(/category=food&nature=spend$/);
  });

  test("typing a search replaces the URL (Back skips the letters)", async ({ signedIn: page }) => {
    await page.goto("/");
    await page.goto("/transactions");
    await page.getByLabel("Search merchant or note").fill("ama");
    await expect(page).toHaveURL(/\/transactions\?q=ama$/);
    await page.getByLabel("Search merchant or note").fill("amaz");
    await expect(page).toHaveURL(/\?q=amaz$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/$/); // straight back to Home, not through "ama"
  });

  test("a row opens its sheet; Back closes it; save → Undo", async ({ signedIn: page, api }) => {
    api.on("PATCH /api/v1/transactions/t-2", () => ({ data: {} }));
    await page.goto("/transactions");
    await page.getByRole("button", { name: /^Edit Amazon/ }).click();
    await expect(page).toHaveURL(/\?txn=t-2$/);
    const sheet = page.getByRole("dialog", { name: "Transaction" });
    await expect(sheet).toBeVisible();
    await page.goBack();
    await expect(sheet).toBeHidden();
    await page.goForward();
    await expect(sheet).toBeVisible();
    await sheet.getByLabel("Category for this transaction").selectOption("health");
    await sheet.getByRole("button", { name: "Save" }).click();
    await expect(sheet).toBeHidden();
    const toast = page.getByRole("status").filter({ hasText: "Shopping → Health, remembered" });
    await expect(toast).toBeVisible();
    await toast.getByRole("button", { name: "Undo" }).click();
    await expect.poll(() => api.callsTo("PATCH", "/api/v1/transactions/t-2").map((c) => (c.body as { category: string }).category)).toEqual(["health", "shopping"]);
  });

  test("Escape closes the sheet", async ({ signedIn: page, isMobile }) => {
    test.skip(isMobile, "phones have no keyboard");
    await page.goto("/transactions?txn=t-1");
    await expect(page.getByRole("dialog", { name: "Transaction" })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog", { name: "Transaction" })).toBeHidden();
    await expect(page).toHaveURL(/\/transactions$/);
  });

  test("a Home period opens Activity with that date range", async ({ signedIn: page, api }) => {
    await page.goto("/transactions?p=week&d=2026-09-02");
    await expect(page.getByRole("list", { name: "Active filters" })).toContainText("2026-08-31 → 2026-09-06");
    // The filter chip can render before the feed request is recorded: wait for it (AOS-313).
    await expect.poll(() => api.calls.some((c) => c.path === "/api/v1/transactions")).toBe(true);
  });

  test("load more appends the next page", async ({ signedIn: page }) => {
    await page.goto("/transactions");
    await page.getByRole("button", { name: "Load more" }).click();
    await expect(page.getByRole("button", { name: /^Edit Zepto/ })).toBeVisible();
    await expect(page.getByRole("button", { name: "Load more" })).toBeHidden();
  });
});
