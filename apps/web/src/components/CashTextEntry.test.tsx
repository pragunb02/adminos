import { http, HttpResponse } from "msw";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import CashQuickAdd from "./CashQuickAdd";
import type { CashParse } from "@/lib/cash";

/** AOS-718: free text or dictation → a preview to check, edit or remove → saved through the cash endpoint. */
describe("cash from words", () => {
  const parsed = (over: Partial<CashParse> = {}): CashParse => ({
    today: "2026-10-17",
    entries: [
      { amount: "200.00", category: "food", note: "Chai", date: "2026-10-17", evidence: "200 chai", categoryFrom: "keyword", warnings: [] },
      { amount: "150.00", category: "other", note: "Raju", date: "2026-10-16", evidence: "150 raju", categoryFrom: "none", warnings: ["category_unknown", "date_assumed"] },
    ],
    unparsed: [{ text: "samosa", reason: "no_amount" }],
    ...over,
  });

  const open = async () => {
    const user = userEvent.setup();
    render(<CashQuickAdd />);
    await user.click(screen.getByRole("button", { name: "Add cash spending" }));
    return user;
  };
  const words = () => screen.getByLabelText("Or say it in words");

  const readAs = async (data: CashParse, text = "200 chai and kal 150 raju, samosa") => {
    let sent: unknown = null;
    server.use(http.post("/api/v1/transactions/cash/parse", async ({ request }) => { sent = await request.json(); return ok(data); }));
    const user = await open();
    await user.type(words(), text);
    await user.click(screen.getByRole("button", { name: "Read it" }));
    await screen.findByRole("heading", { name: "Check before saving" });
    return { user, sent: () => sent };
  };

  it("reads the text into entries to check, with what it assumed and what it couldn't read", async () => {
    const { sent } = await readAs(parsed());
    expect(sent()).toEqual({ text: "200 chai and kal 150 raju, samosa" });
    expect(screen.getByLabelText("Amount for entry 1")).toHaveValue("200");
    expect(screen.getByLabelText("Category for entry 1")).toHaveValue("food");
    expect(screen.getByLabelText("Day for entry 2")).toHaveValue("2026-10-16");
    expect(screen.getByLabelText("Note for entry 2")).toHaveValue("Raju");
    expect(screen.getByText("Pick a category: it wasn't clear.")).toBeInTheDocument();
    expect(screen.getByText("“kal” or “parso” was read as the past.")).toBeInTheDocument();
    expect(screen.getByText("“samosa”: no amount")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save 2 entries" })).toBeEnabled();
  });

  it("saves each entry once, on its day, with its own reference, then says so", async () => {
    const bodies: Record<string, unknown>[] = [];
    server.use(http.post("/api/v1/transactions/cash", async ({ request }) => { bodies.push((await request.json()) as never); return ok({ saved: true, duplicate: false }); }));
    const { user } = await readAs(parsed());
    await user.selectOptions(screen.getByLabelText("Category for entry 2"), "health");
    expect(screen.queryByText("Pick a category: it wasn't clear.")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save 2 entries" }));
    expect(await screen.findByRole("status")).toHaveTextContent("2 cash entries saved (₹350)");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(bodies).toMatchObject([
      { amount: "200", category: "food", note: "Chai", date: "2026-10-17" },
      { amount: "150", category: "health", note: "Raju", date: "2026-10-16" },
    ]);
    expect(bodies[0].clientRef).not.toBe(bodies[1].clientRef);
    expect(bodies.every((b) => !("occurredAt" in b))).toBe(true);
  });

  it("an edited amount, note or day is what's saved; an empty note isn't sent", async () => {
    const bodies: Record<string, unknown>[] = [];
    server.use(http.post("/api/v1/transactions/cash", async ({ request }) => { bodies.push((await request.json()) as never); return ok({ saved: true, duplicate: false }); }));
    const { user } = await readAs(parsed({ entries: [parsed().entries[0]], unparsed: [] }));
    await user.clear(screen.getByLabelText("Amount for entry 1"));
    await user.type(screen.getByLabelText("Amount for entry 1"), "₹2a50");
    await user.clear(screen.getByLabelText("Note for entry 1"));
    fireEvent.change(screen.getByLabelText("Day for entry 1"), { target: { value: "2026-10-15" } });
    await user.click(screen.getByRole("button", { name: "Save 1 entry" }));
    expect(await screen.findByRole("status")).toHaveTextContent("1 cash entry saved (₹250)");
    expect(bodies).toEqual([expect.objectContaining({ amount: "250", date: "2026-10-15" })]);
    expect(bodies[0]).not.toHaveProperty("note");
  });

  it("a bad amount or a day after today blocks saving and says why", async () => {
    const { user } = await readAs(parsed({ unparsed: [] }));
    await user.clear(screen.getByLabelText("Amount for entry 1"));
    expect(screen.getByText("Type an amount, e.g. 40 or 249.50.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save 2 entries" })).toBeDisabled();
    await user.type(screen.getByLabelText("Amount for entry 1"), "40");
    fireEvent.change(screen.getByLabelText("Day for entry 2"), { target: { value: "2026-10-18" } });
    expect(screen.getByText("Pick a day up to today.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save 2 entries" })).toBeDisabled();
    expect(screen.getByLabelText("Day for entry 2")).toHaveAttribute("max", "2026-10-17");
  });

  it("an entry can be removed, and with none left nothing can be saved", async () => {
    const { user } = await readAs(parsed());
    await user.click(screen.getByRole("button", { name: "Remove entry 1" }));
    expect(screen.getByRole("button", { name: "Save 1 entry" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Remove entry 1" }));
    expect(screen.getByText("Nothing to save yet.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save 0 entries" })).toBeDisabled();
  });

  it("start over goes back to the words, which are kept", async () => {
    const { user } = await readAs(parsed());
    await user.click(screen.getByRole("button", { name: "Start over" }));
    expect(words()).toHaveValue("200 chai and kal 150 raju, samosa");
  });

  it("a failed save keeps only what wasn't saved, and saving again reuses its reference", async () => {
    const refs: string[] = [];
    let n = 0;
    server.use(http.post("/api/v1/transactions/cash", async ({ request }) => {
      const b = (await request.json()) as { clientRef: string; amount: string };
      refs.push(b.clientRef);
      return b.amount === "150" && ++n === 1 ? fail(400, "VALIDATION_001", "date: cash is logged once it's spent") : ok({ saved: true, duplicate: false });
    }));
    const { user } = await readAs(parsed({ unparsed: [] }));
    await user.click(screen.getByRole("button", { name: "Save 2 entries" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("1 not saved yet: check it and save again.");
    expect(screen.getByText("date: cash is logged once it's spent")).toBeInTheDocument();
    expect(screen.getByLabelText("Amount for entry 1")).toHaveValue("150");
    await user.click(screen.getByRole("button", { name: "Save 1 entry" }));
    expect(await screen.findByRole("status")).toHaveTextContent("1 cash entry saved (₹150)");
    expect(refs).toHaveLength(3);
    expect(refs[1]).toBe(refs[2]);
  });

  it("two failures say so, a proxy error page is a server message, and no connection says so", async () => {
    const { user } = await readAs(parsed({ unparsed: [] }));
    server.use(http.post("/api/v1/transactions/cash", () => new Response(null, { status: 502, statusText: "Bad Gateway" })));
    await user.click(screen.getByRole("button", { name: "Save 2 entries" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("2 not saved yet: check them and save again.");
    expect(screen.getAllByText("Bad Gateway")).toHaveLength(2);
    server.use(http.post("/api/v1/transactions/cash", () => HttpResponse.error()));
    await user.click(screen.getByRole("button", { name: "Save 2 entries" }));
    expect(await screen.findAllByText("Couldn't reach AdminOS. Check the connection and try again.")).toHaveLength(2);
  });

  it("no connection while reading says so", async () => {
    server.use(http.post("/api/v1/transactions/cash/parse", () => HttpResponse.error()));
    const user = await open();
    await user.type(words(), "chai 40");
    await user.click(screen.getByRole("button", { name: "Read it" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Couldn't reach AdminOS.");
    expect(screen.getByRole("button", { name: "Read it" })).toBeEnabled();
  });

  it("a reading that fails shows why, and the text stays to try again", async () => {
    server.use(http.post("/api/v1/transactions/cash/parse", () => fail(400, "VALIDATION_001", "text: at most 500 characters")));
    const user = await open();
    await user.type(words(), "chai 40");
    await user.click(screen.getByRole("button", { name: "Read it" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("text: at most 500 characters");
    expect(words()).toHaveValue("chai 40");
  });

  it("dictated text (inserted at once) can be read, and Ctrl or Cmd+Enter reads it", async () => {
    server.use(http.post("/api/v1/transactions/cash/parse", () => ok(parsed())));
    const user = await open();
    expect(screen.getByRole("button", { name: "Read it" })).toBeDisabled();
    expect(words()).toHaveAttribute("inputmode", "text");
    // The keyboard's mic inserts the whole phrase in one input event.
    fireEvent.change(words(), { target: { value: "200 chai and kal 150 raju" } });
    expect(screen.getByRole("button", { name: "Read it" })).toBeEnabled();
    words().focus();
    await user.keyboard("{Control>}{Enter}{/Control}");
    expect(await screen.findByRole("heading", { name: "Check before saving" })).toBeInTheDocument();
  });

  it("untrusted words from the API are shown as text, never as HTML", async () => {
    const evil = '<img src=x onerror="alert(1)">';
    await readAs(parsed({
      entries: [{ ...parsed().entries[0], note: evil, category: "emi" }],
      unparsed: [{ text: "<script>alert(1)</script>", reason: "too_large" }],
    }));
    expect(screen.getByLabelText("Note for entry 1")).toHaveValue(evil);
    expect(screen.getByText("“<script>alert(1)</script>”: over ₹1 crore")).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull();
    expect(document.querySelector("script")).toBeNull();
    // A category the quick buttons don't have is still offered.
    expect(within(screen.getByLabelText("Category for entry 1")).getByRole("option", { name: "EMI" })).toBeInTheDocument();
  });
});
