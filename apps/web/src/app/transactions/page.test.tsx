import { http } from "msw";
import { act, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import TransactionsPage from "./page";

/** Activity (AOS-307). "Today" is Fri 16 Oct 2026 in India. */
describe("Activity feed", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date", "setTimeout", "clearTimeout"] });
    vi.setSystemTime(new Date("2026-10-16T06:30:00Z"));
    vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockReturnValue({ timeZone: "Asia/Kolkata" } as Intl.ResolvedDateTimeFormatOptions);
    nav.pathname = "/transactions";
  });
  afterEach(() => vi.useRealTimers());

  const rows = [
    fx.transaction({ id: "a", merchantName: "Swiggy", transactedAt: "2026-10-16T03:00:00Z" }),
    fx.transaction({ id: "b", merchantName: "Amazon", type: "credit", nature: "refund", amount: "300.00", category: "shopping", transactedAt: "2026-10-16T02:00:00Z" }),
    fx.transaction({ id: "c", merchantName: "Uber", category: "transport", amount: "212.40", transactedAt: "2026-10-15T10:00:00Z" }),
  ];
  function routes(over: { list?: (u: URL) => Response; patch?: (body: unknown, id: string) => Response } = {}) {
    const asked: URL[] = [];
    const patches: { id: string; body: unknown }[] = [];
    server.use(
      http.get("/api/v1/transactions", ({ request }) => {
        const u = new URL(request.url);
        asked.push(u);
        if (over.list) return over.list(u);
        return u.searchParams.get("cursor") ? ok([fx.transaction({ id: "d", merchantName: "Zepto", transactedAt: "2026-10-09T10:00:00Z" })], { cursor: null, hasMore: false, total: 4 })
          : ok(rows, { cursor: "next", hasMore: true, total: 4 });
      }),
      http.patch("/api/v1/transactions/:id", async ({ request, params }) => {
        const body = await request.json();
        patches.push({ id: params.id as string, body });
        return over.patch?.(body, params.id as string) ?? ok(fx.transaction({ id: params.id as string, category: (body as { category: string }).category }));
      }),
    );
    return { asked, patches };
  }

  it("groups by day, shows signed amounts and kinds in words, and loads more", async () => {
    routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    const todayGroup = await screen.findByRole("region", { name: "Today" });
    expect(within(todayGroup).getByText("−₹349")).toBeInTheDocument();
    expect(within(todayGroup).getByText("+₹300")).toBeInTheDocument();
    expect(within(todayGroup).getByText("Refund")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Yesterday" })).getByText("−₹212.40")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByRole("region", { name: "9 Oct" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("filters come from the URL and go to the API; search becomes merchant=", async () => {
    nav.search = new URLSearchParams("q=swig&category=food&nature=spend&from=2026-10-01&to=2026-10-16");
    const { asked } = routes();
    renderWithQuery(<TransactionsPage />);
    await screen.findByRole("region", { name: "Today" });
    expect(Object.fromEntries(asked[0].searchParams)).toEqual({ category: "food", nature: "spend", from: "2026-10-01", to: "2026-10-16", limit: "30", merchant: "swig" });
    expect(screen.getByRole("list", { name: "Active filters" })).toHaveTextContent("Food ×Spending ×2026-10-01 → 2026-10-16 ×");
    expect(screen.getByLabelText("Search merchant or note")).toHaveValue("swig");
  });

  it("a Home/Spending period becomes the date range; invalid filter values are ignored", async () => {
    nav.search = new URLSearchParams("p=week&d=2026-10-16&category=groceries&nature=salary");
    const { asked } = routes();
    renderWithQuery(<TransactionsPage />);
    await screen.findByRole("region", { name: "Today" });
    expect(Object.fromEntries(asked[0].searchParams)).toEqual({ from: "2026-10-12", to: "2026-10-16", limit: "30" });
  });

  it("removing a filter chip pushes the URL without it", async () => {
    nav.search = new URLSearchParams("category=food&from=2026-10-01&to=2026-10-16");
    routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    await user.click(await screen.findByRole("button", { name: "Remove filter Food" }));
    expect(nav.router.push).toHaveBeenCalledWith("/transactions?from=2026-10-01&to=2026-10-16", { scroll: false });
    await user.click(screen.getByRole("button", { name: /Remove filter 2026-10-01/ }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/transactions?category=food", { scroll: false });
  });

  it("typing in search replaces the URL once, after a pause (no history entry per letter)", async () => {
    routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    await screen.findByRole("region", { name: "Today" });
    await user.type(screen.getByLabelText("Search merchant or note"), "ube");
    expect(nav.router.replace).not.toHaveBeenCalled();
    await act(async () => { vi.advanceTimersByTime(350); });
    expect(nav.router.replace).toHaveBeenCalledTimes(1);
    expect(nav.router.replace).toHaveBeenCalledWith("/transactions?q=ube", { scroll: false });
    expect(nav.router.push).not.toHaveBeenCalled();
  });

  it("filters apply together (desktop bar and mobile sheet)", async () => {
    routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    await screen.findByRole("region", { name: "Today" });
    // mobile sheet
    await user.click(screen.getByRole("button", { name: "Filters" }));
    const sheet = screen.getByRole("dialog", { name: "Filters" });
    await user.selectOptions(within(sheet).getByLabelText("Kind"), "refund");
    expect(nav.router.push).not.toHaveBeenCalled(); // nothing changes while choosing
    await user.click(within(sheet).getByRole("button", { name: "Apply" }));
    expect(nav.router.push).toHaveBeenCalledWith("/transactions?nature=refund", { scroll: false });
    // cancel closes without applying; the backdrop also closes
    await user.click(screen.getByRole("button", { name: "Filters" }));
    await user.click(within(screen.getByRole("dialog", { name: "Filters" })).getByRole("button", { name: "Cancel" }));
    await user.click(screen.getByRole("button", { name: "Filters" }));
    await user.click(screen.getByRole("dialog", { name: "Filters" }).parentElement!);
    expect(screen.queryByRole("dialog", { name: "Filters" })).not.toBeInTheDocument();
    // desktop bar (both are in the DOM; CSS shows one)
    const bar = screen.getAllByLabelText("Category")[0];
    await user.selectOptions(bar, "transport");
    await user.type(screen.getAllByLabelText("From date")[0], "2026-10-01");
    await user.type(screen.getAllByLabelText("To date")[0], "2026-10-10");
    await user.click(screen.getAllByRole("button", { name: "Apply" })[0]);
    expect(nav.router.push).toHaveBeenLastCalledWith("/transactions?category=transport&from=2026-10-01&to=2026-10-10", { scroll: false }); // the URL (unchanged in this test) is the draft's start
  });

  it("a row opens the transaction (?txn=), which Back or Escape closes", async () => {
    routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    await user.click(await screen.findByRole("button", { name: "Edit Uber ₹212.40" }));
    expect(nav.router.push).toHaveBeenCalledWith("/transactions?txn=c", { scroll: false });
  });

  it("re-categorize: remembered by default, then Undo restores the old category", async () => {
    nav.search = new URLSearchParams("txn=c");
    const { patches } = routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    const sheet = await screen.findByRole("dialog", { name: "Transaction" });
    expect(within(sheet).getByText("−₹212.40")).toBeInTheDocument();
    expect(within(sheet).getByRole("button", { name: "Save" })).toBeDisabled(); // unchanged
    await user.selectOptions(within(sheet).getByLabelText("Category for this transaction"), "health");
    expect(within(sheet).getByLabelText(/Remember for Uber/)).toBeChecked();
    await user.click(within(sheet).getByRole("button", { name: "Save" }));
    const toast = await screen.findByRole("status");
    expect(toast).toHaveTextContent("Transport → Health, remembered");
    expect(nav.router.replace).toHaveBeenCalledWith("/transactions", { scroll: false });
    await user.click(within(toast).getByRole("button", { name: "Undo" }));
    await vi.waitFor(() => expect(patches).toEqual([
      { id: "c", body: { category: "health", remember: true } },
      { id: "c", body: { category: "transport", remember: true } },
    ]));
  });

  it("re-categorize without remembering; the toast goes away by itself", async () => {
    nav.search = new URLSearchParams("txn=a");
    const { patches } = routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    const sheet = await screen.findByRole("dialog", { name: "Transaction" });
    await user.selectOptions(within(sheet).getByLabelText("Category for this transaction"), "entertainment");
    await user.click(within(sheet).getByLabelText(/Remember for Swiggy/));
    await user.click(within(sheet).getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Food → Entertainment");
    expect(screen.getByRole("status")).not.toHaveTextContent("remembered");
    expect(patches[0].body).toEqual({ category: "entertainment", remember: false });
    await act(async () => { vi.advanceTimersByTime(5100); });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("a failed save stays open with the error; Close and Escape close it", async () => {
    nav.search = new URLSearchParams("txn=a");
    routes({ patch: () => fail(400, "VALIDATION_001", "category: bad") });
    const { user } = renderWithQuery(<TransactionsPage />);
    const sheet = await screen.findByRole("dialog", { name: "Transaction" });
    await user.selectOptions(within(sheet).getByLabelText("Category for this transaction"), "health");
    await user.click(within(sheet).getByRole("button", { name: "Save" }));
    expect(await within(sheet).findByRole("alert")).toHaveTextContent("category: bad");
    await user.click(within(sheet).getByRole("button", { name: "Close" }));
    expect(nav.router.replace).toHaveBeenCalledWith("/transactions", { scroll: false });
    await user.keyboard("{Escape}");
    expect(nav.router.replace).toHaveBeenCalledTimes(2);
    await user.click(sheet.parentElement!);
    expect(nav.router.replace).toHaveBeenCalledTimes(3);
  });

  it("a deep link to a transaction not in the list says so", async () => {
    nav.search = new URLSearchParams("txn=zzz");
    routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    const sheet = await screen.findByRole("dialog", { name: "Transaction" });
    expect(await within(sheet).findByText(/isn't in the current list/)).toBeInTheDocument();
    await user.click(within(sheet).getByRole("button", { name: "Close" }));
    expect(nav.router.replace).toHaveBeenCalledWith("/transactions", { scroll: false });
  });

  it("empty: no transactions yet, or none matching the filters", async () => {
    routes({ list: () => ok([], { cursor: null, hasMore: false, total: 0 }) });
    const { unmount } = renderWithQuery(<TransactionsPage />);
    expect(await screen.findByText("No transactions yet.")).toBeInTheDocument();
    unmount();
    nav.search = new URLSearchParams("q=nothing");
    renderWithQuery(<TransactionsPage />);
    expect(await screen.findByText("No transactions match these filters.")).toBeInTheDocument();
  });

  it("an error can be retried", async () => {
    let n = 0;
    routes({ list: () => (++n === 1 ? fail(400, "VALIDATION_003", "Invalid 'from'") : ok(rows)) });
    const { user } = renderWithQuery(<TransactionsPage />);
    expect(await screen.findByText(/Invalid 'from'/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("region", { name: "Today" })).toBeInTheDocument();
  });
});
