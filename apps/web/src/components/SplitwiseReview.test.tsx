import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { fx } from "../../test/fixtures";
import { renderWithQuery } from "../../test/render";
import type { SplitEntry } from "@/lib/splitwise";
import SplitwiseReview from "./SplitwiseReview";

const entries: SplitEntry[] = [
  fx.splitEntry(),
  fx.splitEntry({ id: "sw-2", kind: "payment", effect: "settle_in", description: "Payment", counterparty: "Asha R.", cost: "1500.00", ownerNet: "-1500.00", ownerShare: "0.00",
    reason: "same amount, same day; the friend's name isn't in the bank record",
    transaction: fx.transaction({ id: "t-cr", type: "credit", merchantName: "UPI Credit", amount: "1500.00", accountLast4: "1234" }) }),
  fx.splitEntry({ id: "sw-3", kind: "payment", effect: "settle_out", description: "Payment", counterparty: "Kabir M.", cost: "300.00", ownerShare: "0.00", reason: null }),
  fx.splitEntry({ id: "sw-4", state: "unmatched", description: "Late card bill", transaction: null, reason: null }),
  fx.splitEntry({ id: "sw-5", state: "applied", effect: "owed_spend", description: "<img src=x onerror=alert(1)>", ownerShare: "300.00" }),
  fx.splitEntry({ id: "sw-6", state: "ignored", description: "Old trip" }),
  fx.splitEntry({ id: "sw-7", state: "no_effect", description: "Their lunch" }),
];

function routes(list: SplitEntry[] = entries, net = "1200.00", action?: () => Response) {
  const calls: string[] = [];
  server.use(
    http.get("/api/v1/splitwise/entries", () => ok(list)),
    http.get("/api/v1/splitwise/summary", () => ok({ balances: [{ currency: "INR", net }], counts: {} })),
    http.post("/api/v1/splitwise/entries/:id/:action", ({ params }) => { calls.push(`${params.id}/${params.action}`); return action ? action() : ok({ id: params.id }); }),
  );
  return calls;
}

describe("Splitwise in Review (AOS-911)", () => {
  it("asks about each unclear match, with both sides and the reason", async () => {
    routes();
    renderWithQuery(<SplitwiseReview />);
    const list = await screen.findByRole("list", { name: "Splitwise matches to review" });
    const bill = within(list).getByRole("listitem", { name: "Your share of a bill you paid?" });
    expect(bill).toHaveTextContent("Dinner at Toit");
    expect(bill).toHaveTextContent("your share ₹1,000 of ₹4,000");
    expect(bill).toHaveTextContent("TOIT BREWP");
    expect(bill).toHaveTextContent("same amount, 1 day(s) later");
    const payback = within(list).getByRole("listitem", { name: "A friend paying you back?" });
    expect(payback).toHaveTextContent("Payment · Asha R.");
    expect(payback).toHaveTextContent("+₹1,500");
    expect(payback).toHaveTextContent("··1234");
    expect(within(list).getByRole("listitem", { name: "You paying a friend back?" })).toHaveTextContent("₹300");
    expect(screen.getByText(/Friends owe you ₹1,200/)).toBeInTheDocument();
  });

  it("confirm and reject send the decision", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<SplitwiseReview />);
    const list = await screen.findByRole("list", { name: "Splitwise matches to review" });
    await user.click(within(within(list).getByRole("listitem", { name: "Your share of a bill you paid?" })).getByRole("button", { name: "Yes, that's it" }));
    await user.click(within(within(list).getByRole("listitem", { name: "A friend paying you back?" })).getByRole("button", { name: "Not this one" }));
    await vi.waitFor(() => expect(calls).toEqual(["sw-1/confirm", "sw-2/reject"]));
  });

  it("entries waiting for a bank record can be ignored; applied ones undone; ignored ones looked at again", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<SplitwiseReview />);
    const waiting = await screen.findByRole("list", { name: "Splitwise entries waiting for a bank record", hidden: true });
    await user.click(screen.getByText("Waiting for a bank record (1)"));
    await user.click(within(within(waiting).getByRole("listitem", { name: "Late card bill" })).getByRole("button", { name: "Ignore" }));
    await user.click(screen.getByText("Applied (1) · ignored (1)"));
    const applied = screen.getByRole("list", { name: "Applied Splitwise entries" });
    expect(applied).toHaveTextContent("your share, owed to a friend");
    await user.click(within(applied).getByRole("button", { name: "Undo" }));
    await user.click(within(applied).getByRole("button", { name: "Look again" }));
    await vi.waitFor(() => expect(calls).toEqual(["sw-4/ignore", "sw-5/undo", "sw-6/undo"]));
    expect(screen.queryByText("Their lunch")).not.toBeInTheDocument();
  });

  it("descriptions from the export render as text, never as HTML", async () => {
    routes();
    const { user } = renderWithQuery(<SplitwiseReview />);
    await screen.findByRole("list", { name: "Splitwise matches to review" });
    await user.click(screen.getByText("Applied (1) · ignored (1)"));
    expect(screen.getByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull();
  });

  it("a failed action says why", async () => {
    routes(entries, "0.00", () => fail(409, "SPLIT_002", "That transaction doesn't fit this entry"));
    const { user } = renderWithQuery(<SplitwiseReview />);
    const list = await screen.findByRole("list", { name: "Splitwise matches to review" });
    await user.click(within(list).getAllByRole("button", { name: "Yes, that's it" })[0]);
    expect(await screen.findByRole("alert")).toHaveTextContent("doesn't fit this entry");
    expect(screen.getByText(/settled up/)).toBeInTheDocument();
  });

  it("says when the owner owes friends", async () => {
    routes(entries, "-450.50");
    renderWithQuery(<SplitwiseReview />);
    expect(await screen.findByText(/You owe friends ₹450.50/)).toBeInTheDocument();
  });

  it("shows nothing until there is something, or when nothing was imported", async () => {
    routes([]);
    const { container } = renderWithQuery(<SplitwiseReview />);
    await vi.waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("a list without suggestions or waiting entries shows only what was applied, and no balance without rupees", async () => {
    server.use(
      http.get("/api/v1/splitwise/entries", () => ok([fx.splitEntry({ state: "applied" })])),
      http.get("/api/v1/splitwise/summary", () => ok({ balances: [{ currency: "EUR", net: "30.00" }], counts: {} })),
    );
    renderWithQuery(<SplitwiseReview />);
    expect(await screen.findByText("Applied (1)")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Splitwise matches to review" })).not.toBeInTheDocument();
    expect(screen.queryByText(/owe/)).not.toBeInTheDocument();
  });

  it("an error loading entries offers a retry", async () => {
    let first = true;
    server.use(http.get("/api/v1/splitwise/entries", () => {
      if (first) { first = false; return fail(500, "SERVER_ERROR", "Server error"); }
      return ok([fx.splitEntry()]);
    }));
    const { user } = renderWithQuery(<SplitwiseReview />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Server error");
    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("list", { name: "Splitwise matches to review" })).toBeInTheDocument();
  });
});
