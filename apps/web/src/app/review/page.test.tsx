import { http } from "msw";
import { act, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import ReviewPage from "./page";

const s = (id: string, relation: string, over: object = {}) => ({
  id, relation, score: 9, reason: "same reference 1234",
  primary: fx.transaction({ id: `${id}-p`, merchantName: "Swiggy", sourceType: "sms" }),
  related: fx.transaction({ id: `${id}-r`, merchantName: "SWIGGY", sourceType: "statement" }),
  ...over,
});

describe("Review (AOS-309)", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date", "setTimeout", "clearTimeout"] });
    vi.setSystemTime(new Date("2026-10-16T06:30:00Z"));
    nav.pathname = "/review";
  });
  afterEach(() => vi.useRealTimers());

  function routes(list: unknown[] = [s("d1", "duplicate"), s("t1", "transfer"), s("r1", "refund"), s("v1", "reversal")], decide?: () => Response) {
    const calls: string[] = [];
    let current = [...(list as { id: string }[])];
    server.use(
      http.get("/api/v1/transactions/suggestions", () => ok(current)),
      http.post("/api/v1/transactions/suggestions/:id/:action", ({ params }) => {
        calls.push(`${params.action} ${params.id}`);
        const r = decide?.();
        if (!r) current = current.filter((x) => x.id !== params.id);
        return r ?? ok({ id: params.id });
      }),
    );
    return calls;
  }

  it("lists every kind with both sides, the reason and plain-language answers; tabs count them", async () => {
    routes();
    renderWithQuery(<ReviewPage />);
    const dup = await screen.findByRole("listitem", { name: "Possible duplicate" });
    expect(within(dup).getByText("Swiggy")).toBeInTheDocument();
    expect(within(dup).getByText("SWIGGY")).toBeInTheDocument();
    expect(within(dup).getByText(/same reference 1234 · score 9/)).toBeInTheDocument();
    expect(within(dup).getByRole("button", { name: "Same payment" })).toBeInTheDocument();
    expect(screen.getByRole("listitem", { name: "Transfer between your accounts?" })).toBeInTheDocument();
    expect(screen.getByRole("listitem", { name: "A refund of this purchase?" })).toBeInTheDocument();
    expect(screen.getByRole("listitem", { name: "A failed payment, reversed?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^All/ })).toHaveTextContent("(4)");
    expect(screen.getByRole("button", { name: /^Refunds/ })).toHaveTextContent("(2)"); // refunds + reversals
  });

  it("the tab is in the URL", async () => {
    nav.search = new URLSearchParams("kind=transfer");
    routes();
    const { user } = renderWithQuery(<ReviewPage />);
    await screen.findByRole("listitem", { name: "Transfer between your accounts?" });
    expect(screen.queryByRole("listitem", { name: "Possible duplicate" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^Duplicates/ }));
    expect(nav.router.push).toHaveBeenCalledWith("/review?kind=duplicate", { scroll: false });
    await user.click(screen.getByRole("button", { name: /^All/ }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/review", { scroll: false });
  });

  it("a decision hides the card at once and is sent only after the 5-second Undo window", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<ReviewPage />);
    const dup = await screen.findByRole("listitem", { name: "Possible duplicate" });
    await user.click(within(dup).getByRole("button", { name: "Same payment" }));
    expect(screen.queryByRole("listitem", { name: "Possible duplicate" })).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Merged into one payment");
    expect(calls).toEqual([]); // nothing sent yet: Undo means it never happened
    await act(async () => { vi.advanceTimersByTime(5100); });
    await vi.waitFor(() => expect(calls).toEqual(["confirm d1"]));
  });

  it("Undo brings the card back and sends nothing", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<ReviewPage />);
    const t = await screen.findByRole("listitem", { name: "Transfer between your accounts?" });
    await user.click(within(t).getByRole("button", { name: "No" }));
    expect(screen.getByRole("status")).toHaveTextContent("Kept as spending and income");
    await user.click(screen.getByRole("button", { name: "Undo" }));
    expect(await screen.findByRole("listitem", { name: "Transfer between your accounts?" })).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(6000); });
    expect(calls).toEqual([]);
  });

  it("a second decision sends the first one immediately", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(within(await screen.findByRole("listitem", { name: "A refund of this purchase?" })).getByRole("button", { name: "Yes, a refund" }));
    await user.click(within(screen.getByRole("listitem", { name: "A failed payment, reversed?" })).getByRole("button", { name: "Yes, reversed" }));
    await vi.waitFor(() => expect(calls).toEqual(["confirm r1"]));
    expect(screen.getByRole("status")).toHaveTextContent("Marked as a reversal");
    await act(async () => { vi.advanceTimersByTime(5100); });
    await vi.waitFor(() => expect(calls).toEqual(["confirm r1", "confirm v1"]));
  });

  it("leaving the page sends a pending decision", async () => {
    const calls = routes();
    const { user, unmount } = renderWithQuery(<ReviewPage />);
    await user.click(within(await screen.findByRole("listitem", { name: "Possible duplicate" })).getByRole("button", { name: "Different" }));
    unmount();
    await vi.waitFor(() => expect(calls).toEqual(["reject d1"]));
  });

  it("a decision the API refuses is reported and the card returns", async () => {
    routes([s("d1", "duplicate")], () => fail(404, "SUGGESTION_001", "Suggestion not found or already decided"));
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(within(await screen.findByRole("listitem", { name: "Possible duplicate" })).getByRole("button", { name: "Same payment" }));
    await act(async () => { vi.advanceTimersByTime(5100); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Possible duplicate: Suggestion not found or already decided");
    expect(await screen.findByRole("listitem", { name: "Possible duplicate" })).toBeInTheDocument();
  });

  it("other copy: refunds no, reversals no, duplicates different, transfers yes", async () => {
    routes();
    const { user } = renderWithQuery(<ReviewPage />);
    await user.click(within(await screen.findByRole("listitem", { name: "Transfer between your accounts?" })).getByRole("button", { name: "Yes, a transfer" }));
    expect(screen.getByRole("status")).toHaveTextContent("Marked as a transfer");
    await user.click(within(screen.getByRole("listitem", { name: "A refund of this purchase?" })).getByRole("button", { name: "No" }));
    expect(screen.getByRole("status")).toHaveTextContent("Kept separate");
    await user.click(within(screen.getByRole("listitem", { name: "A failed payment, reversed?" })).getByRole("button", { name: "No" }));
    await user.click(within(screen.getByRole("listitem", { name: "Possible duplicate" })).getByRole("button", { name: "Different" }));
    expect(screen.getByRole("status")).toHaveTextContent("Kept as two payments");
    await user.click(screen.getByRole("button", { name: "Undo" })); // nothing left pending when the test ends
  });

  it("all clear when nothing waits; a tab with nothing says so", async () => {
    routes([s("d1", "duplicate")]);
    nav.search = new URLSearchParams("kind=transfer");
    const { unmount } = renderWithQuery(<ReviewPage />);
    expect(await screen.findByText("Nothing to review in Transfers.")).toBeInTheDocument();
    unmount();
    routes([]);
    nav.search = new URLSearchParams();
    renderWithQuery(<ReviewPage />);
    expect(await screen.findByText("All clear — your totals are final.")).toBeInTheDocument();
  });

  it("an unknown tab shows All; a load error can be retried", async () => {
    nav.search = new URLSearchParams("kind=zzz");
    let broken = true; // the nav badge asks too, so fail every call until the retry
    server.use(http.get("/api/v1/transactions/suggestions", () => (broken ? fail(400, "VALIDATION_003", "List broke") : ok([s("d1", "duplicate")]))));
    const { user } = renderWithQuery(<ReviewPage />);
    const retry = await screen.findByRole("button", { name: "Try again" });
    expect(screen.getAllByRole("alert")[0]).toHaveTextContent("List broke");
    broken = false;
    await user.click(retry);
    expect(await screen.findByRole("listitem", { name: "Possible duplicate" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^All/ })).toHaveAttribute("aria-pressed", "true");
  });
});
