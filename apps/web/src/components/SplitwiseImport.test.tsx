import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, meta, ok } from "../../test/msw/envelope";
import { renderWithQuery } from "../../test/render";
import { api } from "@/lib/api";
import type { SplitImportResult } from "@/lib/splitwise";
import SplitwiseImport from "./SplitwiseImport";

// As on the other import cards: jsdom's File can't travel through fetch as multipart, so the upload is stubbed.
const forms: FormData[] = [];
function respond(body: unknown) {
  forms.length = 0;
  vi.spyOn(api, "upload").mockImplementation(async (path, form) => { expect(path).toBe("/api/v1/imports/splitwise"); forms.push(form); return body as never; });
}
const result = (over: Partial<SplitImportResult> = {}): SplitImportResult => ({
  importId: "imp-1", rows: 12, alreadyImported: 0, changed: 0, applied: 8, suggested: 2, unmatched: 1, noEffect: 1, unreadableRows: 0, ...over,
});
const exportFile = (header = "\uFEFFDate,Description,Category,Cost,Currency,Dev P.,\"Asha R.\",<b>Kabir</b>") =>
  new File([`${header}\r\n2026-10-03,Dinner,Dining out,4000.00,INR,3000.00,-1500.00,-1500.00\r\n`], "group.csv", { type: "text/csv" });

async function choose(file: File = exportFile()) {
  const r = renderWithQuery(<SplitwiseImport />);
  await r.user.upload(screen.getByLabelText("Splitwise export"), file);
  return r;
}

describe("Import from Splitwise (AOS-911)", () => {
  it("reads the people from the file, asks which one is you, and sends the file with that answer", async () => {
    respond({ success: true, data: result(), error: null, meta });
    const { user } = await choose();
    const who = await screen.findByRole("combobox", { name: "Which one is you?" });
    expect(within(who).getAllByRole("option").map((o) => o.textContent)).toEqual(["Choose your name", "Dev P.", "Asha R.", "<b>Kabir</b>"]);
    expect(screen.getByRole("button", { name: "Import from Splitwise" })).toBeDisabled();
    await user.selectOptions(who, "Dev P.");
    await user.click(screen.getByRole("button", { name: "Import from Splitwise" }));
    const done = await screen.findByRole("status", { name: "Splitwise import result" });
    expect(done).toHaveTextContent("12 rows read, 8 applied");
    expect(done).toHaveTextContent("To review2");
    expect(within(done).getByRole("link", { name: "Review the matches →" })).toHaveAttribute("href", "/review");
    expect(forms[0].get("me")).toBe("Dev P.");
    expect((forms[0].get("file") as File).name).toBe("group.csv");
  });

  it("names are shown as text, never as HTML", async () => {
    await choose();
    await screen.findByRole("combobox", { name: "Which one is you?" });
    expect(document.querySelector("b")).toBeNull();
  });

  it("refuses a file that isn't a Splitwise export before sending anything", async () => {
    const spy = vi.spyOn(api, "upload");
    await choose(new File(["Date,Amount,Narration\n2026-10-03,100,UPI"], "bank.csv", { type: "text/csv" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("doesn't look like a Splitwise export");
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import from Splitwise" })).toBeDisabled();
    expect(spy).not.toHaveBeenCalled();
  });

  it.each([
    ["an unclosed quote", "Date,Description,Category,Cost,Currency,\"Dev P.,Asha R."],
    ["an empty member column", "Date,Description,Category,Cost,Currency,Dev P.,  "],
    ["only one member", "Date,Description,Category,Cost,Currency,Dev P."],
  ])("refuses %s", async (_what, header) => {
    await choose(exportFile(header));
    expect(await screen.findByRole("alert")).toHaveTextContent("doesn't look like a Splitwise export");
  });

  it("reports changed rows, unreadable rows, and hides the Review link when nothing waits", async () => {
    respond({ success: true, data: result({ changed: 2, unreadableRows: 1, suggested: 0, unmatched: 0, rows: 1, applied: 1 }), error: null, meta });
    const { user } = await choose();
    await user.selectOptions(await screen.findByRole("combobox", { name: "Which one is you?" }), "Asha R.");
    await user.click(screen.getByRole("button", { name: "Import from Splitwise" }));
    const done = await screen.findByRole("status");
    expect(done).toHaveTextContent("1 row read, 1 applied");
    expect(done).toHaveTextContent("2 rows changed in Splitwise");
    expect(done).toHaveTextContent("1 row couldn't be read");
    expect(within(done).queryByRole("link")).not.toBeInTheDocument();
  });

  it("shows the server's error, or a fallback", async () => {
    respond({ success: false, data: null, error: { code: "SPLITWISE_ME_UNKNOWN", message: "Your name isn't a column in this export" }, meta });
    const { user } = await choose();
    await user.selectOptions(await screen.findByRole("combobox"), "Dev P.");
    await user.click(screen.getByRole("button", { name: "Import from Splitwise" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Your name isn't a column");
    respond({ success: false, data: null, error: null, meta });
    await user.click(screen.getByRole("button", { name: "Import from Splitwise" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The export couldn't be imported.");
  });

  it("choosing no file clears the form", async () => {
    const { user } = await choose();
    await screen.findByRole("combobox");
    await user.upload(screen.getByLabelText("Splitwise export"), [] as unknown as File);
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("past imports can be undone, after a second click", async () => {
    let imports = [{ id: "imp-1", source: "splitwise_csv", rows: 12, createdAt: "2026-10-16T06:00:00Z" }];
    const deleted: string[] = [];
    server.use(
      http.get("/api/v1/splitwise/imports", () => ok(imports)),
      http.delete("/api/v1/splitwise/imports/:id", ({ params }) => { deleted.push(String(params.id)); imports = []; return ok({ id: params.id }); }),
    );
    const { user } = renderWithQuery(<SplitwiseImport />);
    const past = await screen.findByRole("region", { name: "Past Splitwise imports" });
    expect(past).toHaveTextContent("12 rows");
    await user.click(within(past).getByRole("button", { name: "Undo import" }));
    await user.click(within(past).getByRole("button", { name: "Keep" }));
    expect(deleted).toEqual([]);
    await user.click(within(past).getByRole("button", { name: "Undo import" }));
    await user.click(within(past).getByRole("button", { name: "Yes, undo it" }));
    await vi.waitFor(() => expect(deleted).toEqual(["imp-1"]));
    await vi.waitFor(() => expect(screen.queryByRole("region", { name: "Past Splitwise imports" })).not.toBeInTheDocument());
  });

  it("a failed undo or list says so", async () => {
    server.use(
      http.get("/api/v1/splitwise/imports", () => ok([{ id: "imp-1", source: "splitwise_csv", rows: 1, createdAt: "2026-10-16T06:00:00Z" }])),
      http.delete("/api/v1/splitwise/imports/:id", () => fail(404, "SPLIT_001", "Not found")),
    );
    const { user } = renderWithQuery(<SplitwiseImport />);
    const past = await screen.findByRole("region", { name: "Past Splitwise imports" });
    await user.click(within(past).getByRole("button", { name: "Undo import" }));
    await user.click(within(past).getByRole("button", { name: "Yes, undo it" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Not found");
  });

  it("an imports list that can't load shows the error", async () => {
    server.use(http.get("/api/v1/splitwise/imports", () => fail(500, "SERVER_ERROR", "Server error")));
    renderWithQuery(<SplitwiseImport />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Server error");
  });
});
