import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { meta } from "../../../test/msw/envelope";
import { renderWithQuery } from "../../../test/render";
import { api } from "@/lib/api";
import ImportPage from "./page";

// jsdom's File can't travel through Node's fetch as multipart (body length mismatch), so the upload
// call itself is stubbed here; the client's multipart behaviour is tested in lib/api.test.ts.
const uploads: FormData[] = [];
function respond(body: (form: FormData) => Promise<unknown> | unknown) {
  uploads.length = 0;
  vi.spyOn(api, "upload").mockImplementation(async (_path, form) => { uploads.push(form); return (await body(form)) as never; });
}
const okEnv = (data: unknown) => ({ success: true, data, error: null, meta });
const failEnv = (code: string, message: string) => ({ success: false, data: null, error: { code, message }, meta });

const result = (over = {}) => ({ bankCode: "hdfc", accountLast4: "1234", periodStart: "2026-09-01", periodEnd: "2026-09-30", rows: 40, accepted: 37, duplicates: 2, skippedRows: 5, warnings: [], ...over });
const csv = new File(["date,amount"], "sept.csv", { type: "text/csv" });
const pdf = new File(["%PDF"], "Sept.PDF", { type: "application/pdf" });

describe("Import a statement (AOS-202)", () => {
  it("Import is disabled until a file is chosen; CSV needs no password", async () => {
    const { user } = renderWithQuery(<ImportPage />);
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
    await user.upload(screen.getByLabelText("Statement file"), csv);
    expect(screen.getByRole("button", { name: "Import" })).toBeEnabled();
    expect(screen.queryByLabelText("Statement password")).not.toBeInTheDocument();
  });

  it("a PDF offers the password field; the import result is summarised", async () => {
    respond(() => okEnv(result({ warnings: ["2 rows had no date"] })));
    const { user } = renderWithQuery(<ImportPage />);
    await user.upload(screen.getByLabelText("Statement file"), pdf);
    await user.type(screen.getByLabelText("Statement password"), "01011990");
    await user.click(screen.getByRole("button", { name: "Import" }));
    const done = await screen.findByRole("status");
    expect(done).toHaveTextContent("37 new transactions imported");
    expect(done).toHaveTextContent("HDFC Bank ·· 1234");
    expect(done).toHaveTextContent("2026-09-01 → 2026-09-30");
    expect(done).toHaveTextContent("2 rows had no date");
    expect(screen.getByRole("link", { name: "See transactions →" })).toHaveAttribute("href", "/transactions");
    expect([...uploads[0].keys()]).toEqual(["file", "password"]);
    expect(uploads[0].get("password")).toBe("01011990");
    expect(screen.getByLabelText("Statement password")).toHaveValue("");
  });

  it("one transaction is singular; an unknown bank is not recognised; no password is not sent", async () => {
    respond(() => okEnv(result({ accepted: 1, bankCode: null, accountLast4: null })));
    const { user } = renderWithQuery(<ImportPage />);
    await user.upload(screen.getByLabelText("Statement file"), csv);
    await user.click(screen.getByRole("button", { name: "Import" }));
    const done = await screen.findByRole("status");
    expect(done).toHaveTextContent("1 new transaction imported");
    expect(done).toHaveTextContent("Not recognised");
    expect([...uploads[0].keys()]).toEqual(["file"]);
  });

  it.each([
    ["PASSWORD_REQUIRED", /password-protected/, true],
    ["PASSWORD_INCORRECT", /didn't work/, true],
    ["UNSUPPORTED", /Not a statement we can read/, false],
  ])("%s error explains itself", async (code, text, asksPassword) => {
    respond(() => failEnv(code, "Not a statement we can read"));
    const { user } = renderWithQuery(<ImportPage />);
    await user.upload(screen.getByLabelText("Statement file"), csv);
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(!!screen.queryByLabelText("Statement password")).toBe(asksPassword);
  });

  it("a failure with no message has a fallback; picking a new file clears it", async () => {
    respond(() => ({ success: false, data: null, error: null, meta }));
    const { user } = renderWithQuery(<ImportPage />);
    await user.upload(screen.getByLabelText("Statement file"), csv);
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The statement couldn't be imported.");
    await user.upload(screen.getByLabelText("Statement file"), pdf);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows Importing… while busy", async () => {
    respond(async () => { await new Promise((r) => setTimeout(r, 50)); return okEnv(result()); });
    const { user } = renderWithQuery(<ImportPage />);
    await user.upload(screen.getByLabelText("Statement file"), csv);
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(screen.getByRole("button", { name: "Importing…" })).toBeDisabled();
    await screen.findByRole("status");
  });
});
