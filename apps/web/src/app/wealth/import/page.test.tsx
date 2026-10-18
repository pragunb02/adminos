import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../../test/msw/server";
import { fail, meta, ok } from "../../../../test/msw/envelope";
import { nav } from "../../../../test/next";
import { renderWithQuery } from "../../../../test/render";
import { wx } from "../../../../test/wealthFixtures";
import { api } from "@/lib/api";
import WealthImportPage from "./page";

// jsdom's File can't travel through Node's fetch as multipart, so the upload itself is stubbed (as for statements).
const uploads: FormData[] = [];
function respond(body: unknown) {
  uploads.length = 0;
  vi.spyOn(api, "upload").mockImplementation(async (_p, form) => { uploads.push(form); return body as never; });
}
const okEnv = (data: unknown) => ({ success: true, data, error: null, meta });
const failEnv = (code: string, message: string) => ({ success: false, data: null, error: { code, message }, meta });
const pdf = new File(["%PDF"], "My CAS.pdf", { type: "application/pdf" });
// The statement password is made per run, so no password-like literal sits in the source.
const statementSecret = () => `cas-${crypto.randomUUID()}`;

function imports(list = [wx.batch()], undo: () => Response = () => ok({ undone: true })) {
  const undone: string[] = [];
  server.use(
    http.get("/api/v1/wealth/imports", () => ok({ imports: list })),
    http.post("/api/v1/wealth/imports/:id/undo", ({ params }) => { undone.push(String(params.id)); return undo(); }),
  );
  return undone;
}

async function fillAndImport(user: ReturnType<typeof renderWithQuery>["user"]): Promise<string> {
  const secret = statementSecret();
  await user.upload(screen.getByLabelText("Statement PDF"), pdf);
  await user.type(screen.getByLabelText("Statement password"), secret);
  await user.click(screen.getByRole("button", { name: "Import" }));
  return secret;
}

describe("Import a mutual fund statement (AOS-820)", () => {
  beforeEach(() => { nav.pathname = "/wealth/import"; });

  it("needs a file and a password before importing", async () => {
    imports([]);
    const { user } = renderWithQuery(<WealthImportPage />);
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
    await user.upload(screen.getByLabelText("Statement PDF"), pdf);
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
    await user.type(screen.getByLabelText("Statement password"), statementSecret());
    expect(screen.getByRole("button", { name: "Import" })).toBeEnabled();
    expect(await screen.findByText("Nothing imported yet.")).toBeInTheDocument();
  });

  it("a result says what was added, what adds up and what needs review", async () => {
    imports();
    respond(okEnv(wx.importResult({ batch: wx.batch({ duplicates: 3 }) })));
    const { user } = renderWithQuery(<WealthImportPage />);
    await fillAndImport(user);
    const done = await screen.findByRole("status", { name: "Import result" });
    expect(done).toHaveTextContent("8 new transactions · 1 holding add up · 1 needs review");
    expect(done).toHaveTextContent("3 already imported earlier.");
    expect(within(done).getByRole("listitem", { name: /needs review/ })).toHaveTextContent("statement says 11 units");
    expect([...uploads[0].keys()]).toEqual(["file", "password"]);
    expect(screen.getByLabelText("Statement password")).toHaveValue("");
  });

  it("singular counts and no review", async () => {
    imports();
    respond(okEnv(wx.importResult({ batch: wx.batch({ added: 1 }), holdings: [{ ...wx.blocked(), status: "reconciled", reason: null }], blocked: 0 })));
    const { user } = renderWithQuery(<WealthImportPage />);
    await fillAndImport(user);
    expect(await screen.findByRole("status", { name: "Import result" })).toHaveTextContent("1 new transaction · 1 holding add up");
  });

  it("two holdings needing review is plural", async () => {
    imports();
    respond(okEnv(wx.importResult({ holdings: [wx.blocked(), wx.blocked({ scheme: "Other fund" })], blocked: 2 })));
    const { user } = renderWithQuery(<WealthImportPage />);
    await fillAndImport(user);
    expect(await screen.findByRole("status", { name: "Import result" })).toHaveTextContent("0 holdings add up · 2 need review");
  });

  it("explains the parser's refusal and never shows the password", async () => {
    imports();
    respond(failEnv("PASSWORD_INCORRECT", "x"));
    const { user } = renderWithQuery(<WealthImportPage />);
    const secret = await fillAndImport(user);
    expect(await screen.findByRole("alert")).toHaveTextContent("That password doesn't open this statement");
    expect(document.body).not.toHaveTextContent(secret);
  });

  it("lists imports and undoes one", async () => {
    const undone = imports([wx.batch(), wx.batch({ id: "b-0", status: "undone", statementFrom: null, statementTo: null, undoneAt: "2026-01-03T00:00:00Z" })]);
    const { user } = renderWithQuery(<WealthImportPage />);
    const list = await screen.findByRole("region", { name: "Your imports" });
    expect(await within(list).findByText(/1 Jan 2025 – 31 Dec 2025 · 8 added/)).toBeInTheDocument();
    expect(within(list).getByText("(undone)")).toBeInTheDocument();
    await user.click(within(list).getByRole("button", { name: "Undo the import of 31 Dec 2025" }));
    await vi.waitFor(() => expect(undone).toEqual(["b-1"]));
  });

  it("a failed undo or list says so", async () => {
    imports([wx.batch({ statementTo: null })], () => fail(404, "WEALTH_001", "Import not found"));
    const { user } = renderWithQuery(<WealthImportPage />);
    await user.click(await screen.findByRole("button", { name: "Undo the import of this statement" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Import not found");
  });

  it("the imports list can fail and be retried", async () => {
    let calls = 0;
    server.use(http.get("/api/v1/wealth/imports", () => (++calls === 1 ? fail(503, "SERVER_ERROR", "Imports are down") : ok({ imports: [] }))));
    const { user } = renderWithQuery(<WealthImportPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Imports are down");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Nothing imported yet.")).toBeInTheDocument();
  });
});
