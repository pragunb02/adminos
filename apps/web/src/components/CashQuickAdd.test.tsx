import { http, HttpResponse } from "msw";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import CashQuickAdd from "./CashQuickAdd";

/** AOS-204: "+ Cash" → amount → tap a category = saved; one clientRef per entry. */
describe("CashQuickAdd", () => {
  const open = async () => {
    const user = userEvent.setup();
    render(<CashQuickAdd />);
    await user.click(screen.getByRole("button", { name: "Add cash spending" }));
    return user;
  };
  const amount = () => screen.getByLabelText("Amount in rupees");
  const category = (name: string) => screen.getByRole("button", { name: new RegExp(name) });

  it("opens with the amount focused and categories disabled until an amount is typed", async () => {
    await open();
    expect(screen.getByRole("dialog", { name: "Add cash spending" })).toBeInTheDocument();
    expect(amount()).toHaveFocus();
    expect(category("Food")).toBeDisabled();
    expect(screen.getByText("Type the amount first.")).toBeInTheDocument();
  });

  it("keeps only digits and a dot, and rejects invalid amounts", async () => {
    const user = await open();
    await user.type(amount(), "₹1,2a5");
    expect(amount()).toHaveValue("125");
    await user.clear(amount());
    await user.type(amount(), "0");
    expect(category("Food")).toBeDisabled();
    await user.clear(amount());
    await user.type(amount(), "1.234");
    expect(category("Food")).toBeDisabled();
    await user.clear(amount());
    await user.type(amount(), "249.50");
    expect(category("Food")).toBeEnabled();
    expect(screen.getByText("Tap a category to save.")).toBeInTheDocument();
  });

  it("tapping a category saves amount, category, note and time, then confirms", async () => {
    let body: Record<string, unknown> = {};
    server.use(http.post("/api/v1/transactions/cash", async ({ request }) => { body = (await request.json()) as never; return ok({ saved: true, duplicate: false }); }));
    const user = await open();
    await user.type(amount(), "250");
    await user.type(screen.getByLabelText("Note"), "  chai  ");
    await user.click(category("Food"));
    expect(await screen.findByRole("status")).toHaveTextContent("₹250 cash saved");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(body).toMatchObject({ amount: "250", category: "food", note: "chai" });
    expect(String(body.clientRef)).toMatch(/^[0-9a-f-]{36}$/);
    expect(Number.isNaN(Date.parse(String(body.occurredAt)))).toBe(false);
  });

  it("an empty note is not sent", async () => {
    let body: Record<string, unknown> = {};
    server.use(http.post("/api/v1/transactions/cash", async ({ request }) => { body = (await request.json()) as never; return ok({ saved: true, duplicate: false }); }));
    const user = await open();
    await user.type(amount(), "40");
    await user.click(category("Transport"));
    await screen.findByRole("status");
    expect(body).not.toHaveProperty("note");
  });

  it("a retry after an error reuses the same clientRef (saved once)", async () => {
    const refs: string[] = [];
    let attempt = 0;
    server.use(http.post("/api/v1/transactions/cash", async ({ request }) => {
      refs.push(String(((await request.json()) as { clientRef: string }).clientRef));
      return ++attempt === 1 ? fail(400, "VALIDATION_001", "amount: must be at most 10000000") : ok({ saved: true, duplicate: false });
    }));
    const user = await open();
    await user.type(amount(), "99");
    await user.click(category("Other"));
    expect(await screen.findByRole("alert")).toHaveTextContent("amount: must be at most");
    await user.click(category("Other"));
    await screen.findByRole("status");
    expect(refs).toHaveLength(2);
    expect(refs[0]).toBe(refs[1]);
  });

  it("a network failure shows a generic retry message", async () => {
    server.use(http.post("/api/v1/transactions/cash", () => HttpResponse.json({ success: false, data: null, error: null, meta: {} }, { status: 500 })));
    const user = await open();
    await user.type(amount(), "10");
    await user.click(category("Food"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Couldn't save. Try again.");
  });

  it("a new entry gets a new clientRef", async () => {
    const refs: string[] = [];
    server.use(http.post("/api/v1/transactions/cash", async ({ request }) => {
      refs.push(((await request.json()) as { clientRef: string }).clientRef);
      return ok({ saved: true, duplicate: false });
    }));
    const user = await open();
    await user.type(amount(), "10");
    await user.click(category("Food"));
    await screen.findByRole("status");
    await user.click(screen.getByRole("button", { name: "Add cash spending" }));
    await user.type(amount(), "20");
    await user.click(category("Food"));
    await vi.waitFor(() => expect(refs).toHaveLength(2));
    expect(refs[0]).not.toBe(refs[1]);
  });

  it("closes on the backdrop or Escape without saving; clicks inside don't close", async () => {
    const user = await open();
    await user.click(screen.getByRole("dialog"));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add cash spending" }));
    await user.click(screen.getByRole("dialog").parentElement!);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add cash spending" }));
    await user.keyboard("a");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("the confirmation disappears after a few seconds", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    server.use(http.post("/api/v1/transactions/cash", () => ok({ saved: true, duplicate: false })));
    const user = await open();
    await user.type(amount(), "5");
    await user.click(category("Food"));
    expect(await screen.findByRole("status")).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(2600); });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    vi.useRealTimers();
  });

  it("does not save twice while a save is in flight", async () => {
    let calls = 0;
    server.use(http.post("/api/v1/transactions/cash", async () => { calls++; await new Promise((r) => setTimeout(r, 50)); return ok({ saved: true, duplicate: false }); }));
    const user = await open();
    await user.type(amount(), "5");
    const food = category("Food");
    await user.click(food);
    expect(screen.getByText("Saving…")).toBeInTheDocument();
    expect(food).toBeDisabled();
    await screen.findByRole("status");
    expect(calls).toBe(1);
  });
});
