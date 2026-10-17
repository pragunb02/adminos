import { expect, test } from "./support/fixtures";
import { fx } from "../test/fixtures";

/** Documents vault in a real browser (AOS-507): upload, filters in the URL, download link, delete with Undo. */
test.describe("Documents", () => {
  test("lists documents; the kind filter is in the URL with back/forward; search replaces it", async ({ signedIn: page }) => {
    await page.goto("/documents");
    await expect(page.getByRole("listitem", { name: "Form 16 FY26" })).toContainText("Tax · PDF · 240 KB");
    await expect(page.getByRole("listitem", { name: "Form 16 FY26" }).getByRole("link", { name: "Download" })).toHaveAttribute("href", "/api/v1/documents/d-1/file");
    await page.getByLabel("Kind", { exact: true }).last().selectOption("identity");
    await expect(page).toHaveURL(/\/documents\?category=identity$/);
    await expect(page.getByRole("listitem", { name: "Form 16 FY26" })).toBeHidden();
    await expect(page.getByRole("listitem", { name: "Passport" })).toBeVisible();
    await page.goBack();
    await expect(page).toHaveURL(/\/documents$/);
    await page.goForward();
    await expect(page).toHaveURL(/category=identity$/);
    await page.getByLabel("Search documents").fill("pass");
    await expect(page).toHaveURL(/category=identity&q=pass$/);
  });

  test("uploading sends the file with its kind and confirms it", async ({ signedIn: page, api }) => {
    api.on("POST /api/v1/documents", () => ({ status: 201, data: fx.document({ id: "d-9", title: "PAN card" }) }));
    await page.goto("/documents");
    const form = page.getByRole("form", { name: "Add a document" });
    await form.getByLabel(/File/).setInputFiles({ name: "pan.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.7 pan") });
    await form.getByLabel("Title (optional)").fill("PAN card");
    await form.getByLabel("Kind").selectOption("identity");
    await form.getByRole("button", { name: "Upload" }).click();
    await expect(form.getByRole("status")).toHaveText("PAN card saved, encrypted.");
    expect(api.callsTo("POST", "/api/v1/documents")).toHaveLength(1);
  });

  test("delete → Undo keeps it; delete → wait sends it", async ({ signedIn: page, api }) => {
    api.on("DELETE /api/v1/documents/:id", () => ({ data: { deleted: true } }));
    await page.goto("/documents");
    const row = page.getByRole("listitem", { name: "Passport" });
    await row.getByRole("button", { name: "Delete" }).click();
    await expect(row).toBeHidden();
    await page.getByRole("status").getByRole("button", { name: "Undo" }).click();
    await expect(row).toBeVisible();
    await page.waitForTimeout(5500);
    expect(api.callsTo("DELETE", "/api/v1/documents/d-2")).toHaveLength(0);
    await row.getByRole("button", { name: "Delete" }).click();
    await expect.poll(() => api.callsTo("DELETE", "/api/v1/documents/d-2").length, { timeout: 8000 }).toBe(1);
  });
});
