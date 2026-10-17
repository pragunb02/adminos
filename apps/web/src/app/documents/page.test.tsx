import { http } from "msw";
import { act, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import { api } from "@/lib/api";
import DocumentsPage, { expiry, size } from "./page";

/** jsdom's File can't travel through Node's fetch as multipart, so uploads are answered here (as in the import tests). */
function answerUploads(reply: (form: FormData) => { success: boolean; data?: unknown; error?: { code: string; message: string } }) {
  const sent: FormData[] = [];
  vi.spyOn(api, "upload").mockImplementation(async (_path, form) => {
    sent.push(form);
    const r = reply(form);
    return { success: r.success, data: (r.data ?? null) as never, error: r.error ?? null, meta: { requestId: "req-test", timestamp: "2026-10-16T06:30:00Z" } };
  });
  return sent;
}

const pdf = () => new File(["%PDF-1.7 form 16"], "Form16.pdf", { type: "application/pdf" });

describe("Documents (AOS-507)", () => {
  beforeEach(() => { nav.pathname = "/documents"; });
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it("lists documents with kind, type, size and expiry, with download links", async () => {
    server.use(http.get("/api/v1/documents", () => ok([fx.document(), fx.document({ id: "d-2", title: "Passport", category: "identity", fileType: "jpeg", sizeBytes: 1_572_864, expiresOn: "2099-01-01" })])));
    renderWithQuery(<DocumentsPage />);
    const form16 = await screen.findByRole("listitem", { name: "Form 16 FY26" });
    expect(within(form16).getByText("Tax · PDF · 240 KB")).toBeInTheDocument();
    expect(within(form16).getByRole("link", { name: "Download" })).toHaveAttribute("href", "/api/v1/documents/d-1/file");
    const passport = screen.getByRole("listitem", { name: "Passport" });
    expect(within(passport).getByText("Identity · JPEG · 1.5 MB")).toBeInTheDocument();
    expect(within(passport).getByText("Expires 1 Jan 2099")).toBeInTheDocument();
  });

  it("uploads a file with its kind, title and expiry, and confirms it", async () => {
    server.use(http.get("/api/v1/documents", () => ok([])));
    const uploads = answerUploads(() => ({ success: true, data: fx.document({ title: "PAN card" }) }));
    const { user } = renderWithQuery(<DocumentsPage />);
    expect(await screen.findByText(/No documents yet/)).toBeInTheDocument();
    const form = screen.getByRole("form", { name: "Add a document" });
    await user.upload(within(form).getByLabelText(/File/), pdf());
    await user.type(within(form).getByLabelText("Title (optional)"), "PAN card");
    await user.selectOptions(within(form).getByLabelText("Kind"), "identity");
    await user.type(within(form).getByLabelText("Expires on (optional)"), "2030-01-01");
    await user.click(within(form).getByRole("button", { name: "Upload" }));
    expect(await within(form).findByRole("status")).toHaveTextContent("PAN card saved, encrypted.");
    const sent = uploads[0];
    expect(sent.get("category")).toBe("identity");
    expect(sent.get("title")).toBe("PAN card");
    expect(sent.get("expiresOn")).toBe("2030-01-01");
    expect((sent.get("file") as File).name).toBe("Form16.pdf");
  });

  it("refuses a file over 20 MB before sending, and explains server refusals", async () => {
    server.use(http.get("/api/v1/documents", () => ok([])));
    let answer = { code: "DOC_005", message: "The documents vault isn't set up on this server yet" };
    const uploads = answerUploads(() => ({ success: false, error: answer }));
    const { user } = renderWithQuery(<DocumentsPage />);
    const form = await screen.findByRole("form", { name: "Add a document" });
    const big = new File([new Uint8Array(20 * 1024 * 1024 + 1)], "big.pdf", { type: "application/pdf" });
    await user.upload(within(form).getByLabelText(/File/), big);
    await user.click(within(form).getByRole("button", { name: "Upload" }));
    expect(within(form).getByRole("alert")).toHaveTextContent("A document can be at most 20 MB.");
    expect(uploads).toHaveLength(0);
    await user.upload(within(form).getByLabelText(/File/), pdf());
    await user.click(within(form).getByRole("button", { name: "Upload" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent("isn't set up on the server yet (Cloudflare R2 settings in .env)");
    answer = { code: "DOC_002", message: "Only PDF, PNG, JPEG, WebP and HEIC files can be stored" };
    await user.click(within(form).getByRole("button", { name: "Upload" }));
    expect(await within(form).findByRole("alert")).toHaveTextContent("Only PDF, PNG, JPEG, WebP and HEIC");
  });

  it("filters by kind (pushes the URL) and search replaces it after typing", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(http.get("/api/v1/documents", () => ok([fx.document()])));
    const { user } = renderWithQuery(<DocumentsPage />);
    await screen.findByRole("listitem", { name: "Form 16 FY26" });
    await user.selectOptions(screen.getByLabelText("Kind", { selector: "select[aria-label]" }), "tax");
    expect(nav.router.push).toHaveBeenLastCalledWith("/documents?category=tax", { scroll: false });
    await user.type(screen.getByLabelText("Search documents"), "form");
    await act(async () => { vi.advanceTimersByTime(400); });
    expect(nav.router.replace).toHaveBeenLastCalledWith("/documents?q=form", { scroll: false });
  });

  it("asks the API with the URL's filters, and says when nothing matches", async () => {
    nav.search = new URLSearchParams("category=vehicle&q=rc");
    let asked = "";
    server.use(http.get("/api/v1/documents", ({ request }) => { asked = new URL(request.url).search; return ok([]); }));
    renderWithQuery(<DocumentsPage />);
    expect(await screen.findByText("No documents match.")).toBeInTheDocument();
    expect(asked).toBe("?category=vehicle&q=rc");
  });

  it("edits title, kind and expiry", async () => {
    let body: unknown;
    server.use(http.get("/api/v1/documents", () => ok([fx.document()])), http.patch("/api/v1/documents/d-1", async ({ request }) => { body = await request.json(); return ok(fx.document()); }));
    const { user } = renderWithQuery(<DocumentsPage />);
    await user.click(within(await screen.findByRole("listitem", { name: "Form 16 FY26" })).getByRole("button", { name: "Edit" }));
    const edit = screen.getByRole("form", { name: "Edit Form 16 FY26" });
    await user.clear(within(edit).getByLabelText("Title"));
    await user.type(within(edit).getByLabelText("Title"), "Form 16 (2025-26)");
    await user.click(within(edit).getByRole("button", { name: "Save" }));
    await vi.waitFor(() => expect(body).toEqual({ title: "Form 16 (2025-26)", category: "tax", expiresOn: "" }));
    expect(screen.queryByRole("form", { name: /Edit/ })).not.toBeInTheDocument();
  });

  it("an edit the API refuses is shown, and cancel closes the form", async () => {
    server.use(http.get("/api/v1/documents", () => ok([fx.document()])), http.patch("/api/v1/documents/d-1", () => fail(400, "VALIDATION_001", "title: at most 120 characters")));
    const { user } = renderWithQuery(<DocumentsPage />);
    await user.click(within(await screen.findByRole("listitem", { name: "Form 16 FY26" })).getByRole("button", { name: "Edit" }));
    const edit = screen.getByRole("form", { name: "Edit Form 16 FY26" });
    await user.click(within(edit).getByRole("button", { name: "Save" }));
    expect(await within(edit).findByRole("alert")).toHaveTextContent("title: at most 120 characters");
    await user.click(within(edit).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("form", { name: /Edit/ })).not.toBeInTheDocument();
  });

  it("delete hides at once; Undo keeps it, otherwise it's deleted after 5 s; a failure is shown", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setTimeout", "clearTimeout"] });
    const deleted: string[] = [];
    let refuse = false;
    server.use(
      http.get("/api/v1/documents", () => ok([fx.document(), fx.document({ id: "d-2", title: "Passport" })])),
      http.delete("/api/v1/documents/:id", ({ params }) => { if (refuse) return fail(503, "DOC_003", "Document storage is unavailable; try again shortly"); deleted.push(String(params.id)); return ok({ deleted: true }); }),
    );
    const { user } = renderWithQuery(<DocumentsPage />);
    await user.click(within(await screen.findByRole("listitem", { name: "Form 16 FY26" })).getByRole("button", { name: "Delete" }));
    expect(screen.queryByRole("listitem", { name: "Form 16 FY26" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Undo" }));
    expect(await screen.findByRole("listitem", { name: "Form 16 FY26" })).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(6000); });
    expect(deleted).toEqual([]);

    await user.click(within(screen.getByRole("listitem", { name: "Form 16 FY26" })).getByRole("button", { name: "Delete" }));
    await user.click(within(screen.getByRole("listitem", { name: "Passport" })).getByRole("button", { name: "Delete" }));
    await vi.waitFor(() => expect(deleted).toEqual(["d-1"]), { timeout: 2000 }); // a second delete sends the first
    refuse = true;
    await act(async () => { vi.advanceTimersByTime(5100); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Passport: Document storage is unavailable");
  });

  it("a load error can be retried", async () => {
    let broken = true;
    server.use(http.get("/api/v1/documents", () => (broken ? fail(500, "SERVER_001", "Internal server error") : ok([]))));
    const { user } = renderWithQuery(<DocumentsPage />);
    const retry = await screen.findByRole("button", { name: "Try again" });
    broken = false;
    await user.click(retry);
    expect(await screen.findByText(/No documents yet/)).toBeInTheDocument();
  });

  it("sizes and expiry read plainly", () => {
    expect(size(900)).toBe("900 B");
    expect(size(245_760)).toBe("240 KB");
    expect(size(1_572_864)).toBe("1.5 MB");
    const today = new Date(2026, 9, 16);
    expect(expiry(null, today)).toBeNull();
    expect(expiry("2026-10-15", today)).toEqual({ text: "Expired", late: true });
    expect(expiry("2026-10-16", today)).toEqual({ text: "Expires today", late: true });
    expect(expiry("2026-10-17", today)).toEqual({ text: "Expires in 1 day", late: true });
    expect(expiry("2026-11-10", today)).toEqual({ text: "Expires in 25 days", late: true });
    expect(expiry("2027-01-01", today)?.late).toBe(false);
  });
});
