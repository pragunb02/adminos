import { expect, expectAccessible, mainNav, test } from "./support/fixtures";

const SCREENS = ["/", "/transactions", "/import", "/subscriptions", "/bills", "/briefings", "/settings", "/anomalies/an-1", "/onboarding"];

test.describe("accessibility (axe, WCAG 2.2 AA: no serious or critical issues)", () => {
  for (const path of SCREENS) {
    test(`${path}`, async ({ signedIn: page }) => {
      await page.goto(path);
      await expect(page.locator("h1, h2").first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      await expectAccessible(page);
    });
  }
  test("/login", async ({ page }) => {
    await page.goto("/login");
    await expectAccessible(page);
  });
  test("cash quick-add sheet", async ({ signedIn: page }) => {
    await page.goto("/");
    await page.getByRole("button", { name: "Add cash spending" }).click();
    await expectAccessible(page);
  });
});

test.describe("keyboard only", () => {
  test("reach the nav, move between pages, add cash and close it", async ({ signedIn: page, isMobile }) => {
    test.skip(isMobile, "phones have no keyboard");
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Good morning, Pragun" })).toBeVisible();
    const target = mainNav(page).getByRole("link", { name: "Bills" });
    for (let i = 0; i < 30 && !(await target.evaluate((el) => el === document.activeElement)); i++) await page.keyboard.press("Tab");
    await expect(target).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/bills$/);
    const cash = page.getByRole("button", { name: "Add cash spending" });
    await cash.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByLabel("Amount in rupees")).toBeFocused();
    await page.keyboard.type("120");
    await page.keyboard.press("Tab"); // note
    await page.keyboard.press("Tab"); // first category
    await page.keyboard.press("Enter");
    await expect(page.getByRole("status")).toHaveText("₹120 cash saved");
    await cash.focus();
    await page.keyboard.press("Enter");
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toBeHidden();
  });
});
