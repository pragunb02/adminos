import { expect, expectAccessible, mainNav, test } from "./support/fixtures";

const SCREENS = ["/", "/spending", "/spending?view=merchants", "/spending?view=trends", "/spending/category/food", "/transactions?txn=t-1", "/transactions", "/review", "/more", "/import", "/subscriptions", "/documents", "/bills", "/briefings", "/settings", "/anomalies/an-1", "/onboarding"];

test.describe("accessibility (axe, WCAG 2.2 AA: no serious or critical issues)", () => {
  for (const path of SCREENS) {
    test(`${path}`, async ({ signedIn: page }) => {
      await page.goto(path);
      await expect(page.getByRole("heading", { level: 2 }).first()).toBeVisible();
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
    await expect(page.getByRole("region", { name: "Where it went" })).toBeVisible();
    const target = mainNav(page).getByRole("link", { name: "Bills" }); // desktop rail
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
