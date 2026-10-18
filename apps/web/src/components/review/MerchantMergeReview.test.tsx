import { http } from "msw";
import { act, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import MerchantMergeReview from "./MerchantMergeReview";

const PATH = "/api/v1/merchants/merge-proposals";

describe("Review → Same merchant? (AOS-709)", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date", "setTimeout", "clearTimeout"] });
    vi.setSystemTime(new Date("2026-10-16T06:30:00Z"));
  });
  afterEach(() => vi.useRealTimers());

  function routes(list = [fx.mergeProposal(), fx.mergeProposal({ id: "mp-2", name: "Kuku FM", names: [{ name: "Kuku FM", payments: 3 }, { name: "Kukufm", payments: 1 }], reasons: ["the same name once cleaned"] })], decide?: () => Response) {
    const calls: string[] = [];
    let current = [...list];
    server.use(
      http.get(PATH, () => ok(current)),
      http.post(`${PATH}/:id/:action`, ({ params }) => {
        calls.push(`${params.action} ${params.id}`);
        const r = decide?.();
        if (!r) current = current.filter((x) => x.id !== params.id);
        return r ?? ok({ id: params.id, merged: params.action === "confirm", name: "x" });
      }),
    );
    return calls;
  }

  it("shows each group with its names, payment counts and reasons", async () => {
    routes();
    renderWithQuery(<MerchantMergeReview />);
    const card = await screen.findByRole("listitem", { name: "Same merchant: Netflix?" });
    expect(within(card).getByText("Are these all Netflix?")).toBeInTheDocument();
    expect(within(card).getByText("NETFLIX.COM")).toBeInTheDocument();
    expect(within(card).getByText("2 payments")).toBeInTheDocument();
    expect(within(card).getByText("1 payment")).toBeInTheDocument();
    expect(within(card).getByText("the same name once cleaned · one is a known brand")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Same merchant?" })).toBeInTheDocument();
  });

  it("with nothing to ask it renders nothing", async () => {
    routes([]);
    const { container } = renderWithQuery(<MerchantMergeReview />);
    await act(async () => { await vi.advanceTimersByTimeAsync(50); });
    expect(container).toBeEmptyDOMElement();
  });

  it("a load error offers a retry", async () => {
    let fails = true;
    server.use(http.get(PATH, () => (fails ? ((fails = false), fail(500, "SERVER_001", "Something broke")) : ok([fx.mergeProposal()]))));
    const { user } = renderWithQuery(<MerchantMergeReview />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Something broke");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("listitem", { name: "Same merchant: Netflix?" })).toBeInTheDocument();
  });

  it("one tap hides the card and sends the merge only after the 5-second Undo window", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<MerchantMergeReview />);
    const card = await screen.findByRole("listitem", { name: "Same merchant: Netflix?" });
    await user.click(within(card).getByRole("button", { name: /^Same merchant$/ }));
    expect(screen.queryByRole("listitem", { name: "Same merchant: Netflix?" })).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Merging into Netflix");
    expect(calls).toEqual([]);
    await act(async () => { vi.advanceTimersByTime(5100); });
    await vi.waitFor(() => expect(calls).toEqual(["confirm mp-1"]));
  });

  it("Undo brings the card back and sends nothing", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<MerchantMergeReview />);
    await user.click(within(await screen.findByRole("listitem", { name: "Same merchant: Kuku FM?" })).getByRole("button", { name: /^Different$/ }));
    expect(screen.getByRole("status")).toHaveTextContent("Kept as separate merchants");
    await user.click(screen.getByRole("button", { name: "Undo" }));
    expect(await screen.findByRole("listitem", { name: "Same merchant: Kuku FM?" })).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(6000); });
    expect(calls).toEqual([]);
  });

  it("a second answer sends the first at once, and leaving sends the pending one", async () => {
    const calls = routes();
    const { user, unmount } = renderWithQuery(<MerchantMergeReview />);
    await user.click(within(await screen.findByRole("listitem", { name: "Same merchant: Netflix?" })).getByRole("button", { name: /^Same merchant$/ }));
    await user.click(within(screen.getByRole("listitem", { name: "Same merchant: Kuku FM?" })).getByRole("button", { name: /^Different$/ }));
    await vi.waitFor(() => expect(calls).toEqual(["confirm mp-1"]));
    unmount();
    await vi.waitFor(() => expect(calls).toEqual(["confirm mp-1", "reject mp-2"]));
  });

  it("an answer the API refuses is reported and the card returns", async () => {
    routes([fx.mergeProposal()], () => fail(404, "MERCHANT_MERGE_001", "Proposal not found or already decided"));
    const { user } = renderWithQuery(<MerchantMergeReview />);
    await user.click(within(await screen.findByRole("listitem", { name: "Same merchant: Netflix?" })).getByRole("button", { name: /^Same merchant$/ }));
    await act(async () => { vi.advanceTimersByTime(5100); });
    expect(await screen.findByRole("alert")).toHaveTextContent("Netflix: Proposal not found or already decided");
    expect(await screen.findByRole("listitem", { name: "Same merchant: Netflix?" })).toBeInTheDocument();
  });

  it("a hostile merchant name is shown as text, never as markup", async () => {
    const evil = '<img src=x onerror="window.__pwned=1">';
    routes([fx.mergeProposal({ name: evil, names: [{ name: evil, payments: 1 }, { name: `${evil} Pvt Ltd`, payments: 1 }] })]);
    const { container } = renderWithQuery(<MerchantMergeReview />);
    expect(await screen.findByText(`Are these all ${evil}?`)).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
    expect((window as unknown as { __pwned?: number }).__pwned).toBeUndefined();
  });
});
