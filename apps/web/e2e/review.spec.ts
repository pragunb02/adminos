import { expect, expectAccessible, test } from "./support/fixtures";
import { fx } from "../test/fixtures";
import type { MockApi } from "./support/mock-api";

/** Review in a real browser (AOS-309): tabs in the URL, a decision waits 5 s for Undo, then is sent. */
function withSuggestions(api: MockApi) {
  const list = [
    fx.suggestion(),
    fx.suggestion({ id: "sg-2", relation: "transfer", reason: "same amount, your two accounts" }),
    fx.suggestion({ id: "sg-3", relation: "refund", reason: "credit from the same merchant" }),
  ];
  api.on("GET /api/v1/transactions/suggestions", () => ({ data: list }))
    .on("POST /api/v1/transactions/suggestions/:id/:action", (_r, u) => {
      const id = u.pathname.split("/")[5];
      list.splice(list.findIndex((s) => s.id === id), 1);
      return { data: { id } };
    });
}

test.describe("Review", () => {
  test("tabs live in the URL; back/forward/refresh restore them", async ({ signedIn: page, api }) => {
    withSuggestions(api);
    await page.goto("/review");
    await expect(page.getByRole("main").getByRole("listitem")).toHaveCount(3);
    await page.getByRole("button", { name: /^Transfers/ }).click();
    await expect(page).toHaveURL(/\/review\?kind=transfer$/);
    await expect(page.getByRole("main").getByRole("listitem")).toHaveCount(1);
    await page.getByRole("button", { name: /^Refunds/ }).click();
    await expect(page).toHaveURL(/\?kind=refund$/);
    await page.goBack();
    await expect(page).toHaveURL(/\?kind=transfer$/);
    await page.reload();
    await expect(page.getByRole("listitem", { name: "Transfer between your accounts?" })).toBeVisible();
    await page.goBack();
    await expect(page).toHaveURL(/\/review$/);
    await page.goForward();
    await expect(page).toHaveURL(/\?kind=transfer$/);
  });

  test("Undo means nothing is sent; otherwise it is sent after 5 s and the card stays gone", async ({ signedIn: page, api }) => {
    withSuggestions(api);
    await page.goto("/review");
    const dup = page.getByRole("listitem", { name: "Possible duplicate" });
    await dup.getByRole("button", { name: "Same payment" }).click();
    await expect(dup).toBeHidden();
    await page.getByRole("status").getByRole("button", { name: "Undo" }).click();
    await expect(dup).toBeVisible();
    await page.waitForTimeout(5500);
    expect(api.calls.filter((c) => c.method === "POST")).toEqual([]);

    await page.getByRole("listitem", { name: "Transfer between your accounts?" }).getByRole("button", { name: "Yes, a transfer" }).click();
    await expect(page.getByRole("status")).toContainText("Marked as a transfer");
    await expect.poll(() => api.callsTo("POST", "/api/v1/transactions/suggestions/sg-2/confirm").length, { timeout: 8000 }).toBe(1);
    await expect(page.getByRole("button", { name: /^All/ })).toContainText("(2)");
    await page.reload();
    await expect(page.getByRole("main").getByRole("listitem")).toHaveCount(2);
  });

  test("leaving the page sends a pending decision", async ({ signedIn: page, api }) => {
    withSuggestions(api);
    await page.goto("/review");
    await page.getByRole("listitem", { name: "A refund of this purchase?" }).getByRole("button", { name: "No" }).click();
    await page.getByRole("navigation", { name: "Main navigation" }).locator("visible=true").getByRole("link", { name: "Home" }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect.poll(() => api.callsTo("POST", "/api/v1/transactions/suggestions/sg-3/reject").length).toBe(1);
  });

  test("all clear when nothing waits", async ({ signedIn: page }) => {
    await page.goto("/review");
    await expect(page.getByText("All clear — your totals are final.")).toBeVisible();
  });

  test("with cards and the Undo bar it is accessible", async ({ signedIn: page, api }) => {
    withSuggestions(api);
    await page.goto("/review");
    await page.getByRole("listitem", { name: "Possible duplicate" }).getByRole("button", { name: "Different" }).click();
    await expect(page.getByRole("status")).toBeVisible();
    await expectAccessible(page);
  });
});
