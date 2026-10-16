import { expect, mainNav, test } from "./support/fixtures";

/** Spending in a real browser (AOS-305): view and period in the URL; category detail and back. */
test.describe("Spending", () => {
  test("categories → a category → back; Merchants view; every step restorable with back/forward and refresh", async ({ signedIn: page }) => {
    await page.goto("/");
    await mainNav(page).getByRole("link", { name: /^Spending/ }).click();
    const list = page.getByRole("region", { name: "By category" });
    await expect(list.getByRole("link", { name: /Shopping/ })).toBeVisible();
    await page.getByRole("button", { name: "Previous period" }).click();
    await expect(page).toHaveURL(/\/spending\?p=month&d=\d{4}-\d{2}-01$/);
    await list.getByRole("link", { name: /Food/ }).click();
    await expect(page).toHaveURL(/\/spending\/category\/food\?p=month&d=/);
    await expect(page.getByRole("heading", { name: "Food" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Last 6 months" }).locator(".recharts-line-curve")).toBeVisible();
    await page.getByRole("link", { name: "← Spending" }).click();
    await expect(page).toHaveURL(/\/spending\?p=month&d=/);
    await page.getByRole("button", { name: "Merchants" }).click();
    await expect(page).toHaveURL(/view=merchants/);
    await expect(page.getByRole("region", { name: "By merchant" }).getByText("Amazon")).toBeVisible();
    await page.reload();
    await expect(page.getByRole("button", { name: "Merchants" })).toHaveAttribute("aria-pressed", "true");
    await page.goBack();
    await expect(page.getByRole("region", { name: "By category" })).toBeVisible();
    await page.goBack();
    await expect(page.getByRole("heading", { name: "Food" })).toBeVisible();
    await page.goForward();
    await page.goForward();
    await expect(page.getByRole("region", { name: "By merchant" })).toBeVisible();
  });

  test("an unknown category says so", async ({ signedIn: page }) => {
    await page.goto("/spending/category/groceries");
    await expect(page.getByRole("heading", { name: "No such category" })).toBeVisible();
  });
});
