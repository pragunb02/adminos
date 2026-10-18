import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { meta } from "../../test/msw/envelope";
import { renderWithQuery } from "../../test/render";
import { api } from "@/lib/api";
import WhatsAppImport, { type WhatsAppImportResult } from "./WhatsAppImport";

// As on the statement page: jsdom's File can't travel through fetch as multipart, so the upload is stubbed.
const paths: string[] = [];
function respond(body: unknown) {
  paths.length = 0;
  vi.spyOn(api, "upload").mockImplementation(async (path) => { paths.push(path); return body as never; });
}
const result = (over: Partial<WhatsAppImportResult> = {}): WhatsAppImportResult => ({
  messages: 40, fromBanks: 30, accepted: 21, duplicates: 4, mandates: 1, ignored: { "one-time password": 3, "balance alert": 1 }, unreadableLines: 0, ...over,
});
const chat = new File(["16/10/26, 09:12 - HDFC Bank: hi"], "_chat.txt", { type: "text/plain" });

async function importChat() {
  const r = renderWithQuery(<WhatsAppImport />);
  expect(screen.getByRole("button", { name: "Import chat" })).toBeDisabled();
  await r.user.upload(screen.getByLabelText("WhatsApp chat export"), chat);
  await r.user.click(screen.getByRole("button", { name: "Import chat" }));
  return r;
}

describe("Import a WhatsApp bank chat (AOS-403)", () => {
  it("uploads the chat and summarises what was imported and skipped", async () => {
    respond({ success: true, data: result(), error: null, meta });
    await importChat();
    const done = await screen.findByRole("status", { name: "WhatsApp import result" });
    expect(paths).toEqual(["/api/v1/imports/whatsapp"]);
    expect(done).toHaveTextContent("21 new transactions imported");
    expect(done).toHaveTextContent("30 of 40");
    expect(screen.getByRole("list", { name: "Skipped messages" })).toHaveTextContent("3 messages skipped: one-time password");
    expect(done).not.toHaveTextContent("read by AI");
  });

  it("says how many messages wait for the AI reader in Review (AOS-705)", async () => {
    respond({ success: true, data: result({ reading: 2 }), error: null, meta });
    await importChat();
    const done = await screen.findByRole("status", { name: "WhatsApp import result" });
    expect(done).toHaveTextContent("2 messages will be read by AI and wait for you in Review.");
    expect(screen.getByRole("link", { name: "Review" })).toHaveAttribute("href", "/review");
  });

  it("explains when the chat has no bank messages, and reports unreadable dates", async () => {
    respond({ success: true, data: result({ fromBanks: 0, accepted: 0, ignored: {}, messages: 1, unreadableLines: 2 }), error: null, meta });
    await importChat();
    const done = await screen.findByRole("status");
    expect(done).toHaveTextContent("None of the 1 message came from a bank");
    expect(done).toHaveTextContent("2 lines had a date");
    expect(screen.queryByRole("list", { name: "Skipped messages" })).not.toBeInTheDocument();
  });

  it("shows the server's error, or a fallback", async () => {
    respond({ success: false, data: null, error: { code: "WHATSAPP_NOT_A_CHAT", message: "This doesn't look like a WhatsApp chat export" }, meta });
    await importChat();
    expect(await screen.findByRole("alert")).toHaveTextContent("doesn't look like a WhatsApp chat export");
  });

  it("falls back to a generic message when the server gives none", async () => {
    respond({ success: false, data: null, error: null, meta });
    await importChat();
    expect(await screen.findByRole("alert")).toHaveTextContent("The chat couldn't be imported.");
  });
});
