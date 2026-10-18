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

  test("read fields: proposals are checked, only the ticked ones are saved, the number as its last 4", async ({ signedIn: page, api }) => {
    api.on("POST /api/v1/documents/:id/read", () => ({ data: {
      source: "ocr", pagesRead: 1, pagesTotal: 1, truncated: false, ai: "no_consent",
      proposals: [
        { field: "expiresOn", value: "2027-03-31", context: "Valid till: 31/03/2027", label: "Valid till", from: "rules" },
        { field: "number", value: "3210", context: "Policy No.: ••••3210", label: "Policy No.", from: "rules" },
        { field: "amount", value: "12500.00", context: "Total Premium: Rs. 12,500.00", label: "Total premium", from: "rules" },
      ],
    } }));
    api.on("PATCH /api/v1/documents/:id", () => ({ data: fx.document({ expiresOn: "2027-03-31", numberLast4: "3210" }) }));
    await page.goto("/documents");
    const row = page.getByRole("listitem", { name: "Form 16 FY26" });
    await row.getByRole("button", { name: "Read fields" }).click();
    const panel = row.getByRole("region", { name: /Proposed fields/ });
    await expect(panel).toContainText("Expires on: 31 Mar 2027");
    await expect(panel).toContainText("Number: •••• 3210");
    await expect(panel).toContainText("AI help is off for documents");
    await panel.getByRole("checkbox", { name: /Amount paid/ }).uncheck();
    await panel.getByRole("button", { name: "Save selected" }).click();
    await expect(row.getByRole("status")).toHaveText("2 fields saved. You'll be reminded before it expires.");
    const [patch] = api.callsTo("PATCH", "/api/v1/documents/d-1");
    expect(patch.body).toEqual({ expiresOn: "2027-03-31", numberLast4: "3210" });
  });

  test("attack: hostile text read from a document renders as text, and no full number reaches the page", async ({ signedIn: page, api }) => {
    let dialogs = 0;
    page.on("dialog", (d) => { dialogs++; void d.dismiss(); });
    api.on("POST /api/v1/documents/:id/read", () => ({ data: {
      source: "text_layer", pagesRead: 1, pagesTotal: 1, truncated: false, ai: "rejected",
      proposals: [{ field: "issuer", value: "<img src=x onerror=alert(1)>", context: "<script>alert(2)</script> Issued by", label: "", from: "ai" }],
    } }));
    await page.goto("/documents");
    const row = page.getByRole("listitem", { name: "Form 16 FY26" });
    await row.getByRole("button", { name: "Read fields" }).click();
    const panel = row.getByRole("region", { name: /Proposed fields/ });
    await expect(panel).toContainText("<img src=x onerror=alert(1)>");
    await expect(panel).toContainText("<script>alert(2)</script>");
    await expect(panel.locator("img, script")).toHaveCount(0);
    await expect(panel).toContainText("couldn't be found in the document");
    expect(dialogs).toBe(0);
    expect(await page.content()).not.toMatch(/\d{9,}/);
  });

  test("a document that can't be read shows why and saves nothing", async ({ signedIn: page, api }) => {
    api.on("POST /api/v1/documents/:id/read", () => ({ status: 422, error: { code: "DOC_008", message: "The image is too large to read" } }));
    await page.goto("/documents");
    const row = page.getByRole("listitem", { name: "Form 16 FY26" });
    await row.getByRole("button", { name: "Read fields" }).click();
    await expect(row.getByRole("alert")).toHaveText("The image is too large to read");
    expect(api.callsTo("PATCH", "/api/v1/documents/d-1")).toHaveLength(0);
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
