import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import TransactionsPage from "./page";

/** Activity as the drill-down of "Where it leaks" (AOS-902). "Today" is Sun 18 Oct 2026 in India. */
describe("Activity lens filters", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-18T06:30:00Z"));
    vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockReturnValue({ timeZone: "Asia/Kolkata" } as Intl.ResolvedDateTimeFormatOptions);
    nav.pathname = "/transactions";
  });
  afterEach(() => vi.useRealTimers());

  function routes(reply?: () => Response) {
    const asked: string[] = [];
    let plain = 0;
    server.use(
      http.get("/api/v1/coach/leaks/transactions", ({ request }) => {
        asked.push(new URL(request.url).search);
        return reply?.() ?? ok([fx.transaction({ id: "z1", merchantName: "Zepto", amount: "600.00", transactedAt: "2026-10-13T18:00:00Z" })], { cursor: null, hasMore: false, total: 1 });
      }),
      http.get("/api/v1/transactions", () => { plain++; return ok([], { cursor: null, hasMore: false, total: 0 }); }),
    );
    return { asked, plain: () => plain };
  }

  it("a lens slice lists the leaks' own transactions and shows each part as a removable filter", async () => {
    nav.search = new URLSearchParams("lens=quick_commerce&from=2026-10-12&to=2026-10-18&merchant=Zepto&late=1&weekday=tue");
    const { asked, plain } = routes();
    const { user } = renderWithQuery(<TransactionsPage />);
    expect(await screen.findByRole("button", { name: "Edit Zepto ₹600" })).toBeInTheDocument();
    expect(asked).toEqual(["?lens=quick_commerce&limit=1000&from=2026-10-12&to=2026-10-18&merchant=Zepto&lateNight=true&weekday=tue"]);
    expect(plain()).toBe(0);
    const chips = screen.getByRole("list", { name: "Active filters" });
    expect(within(chips).getByRole("button", { name: "Remove filter Lens: Quick commerce" })).toBeInTheDocument();
    expect(within(chips).getByRole("button", { name: "Remove filter Merchant: Zepto" })).toBeInTheDocument();
    expect(within(chips).getByRole("button", { name: "Remove filter Late night" })).toBeInTheDocument();
    expect(within(chips).getByRole("button", { name: "Remove filter Tue" })).toBeInTheDocument();
    await user.click(within(chips).getByRole("button", { name: "Remove filter Late night" }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/transactions?from=2026-10-12&to=2026-10-18&lens=quick_commerce&merchant=Zepto&weekday=tue", { scroll: false });
    await user.click(within(chips).getByRole("button", { name: "Remove filter Lens: Quick commerce" }));
    expect(nav.router.push).toHaveBeenLastCalledWith("/transactions?from=2026-10-12&to=2026-10-18", { scroll: false });
  });

  it("all leaks has its own label, and a lens slice has no Load more", async () => {
    nav.search = new URLSearchParams("lens=all");
    routes();
    renderWithQuery(<TransactionsPage />);
    expect(await screen.findByRole("button", { name: "Remove filter All leaks" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("bad lens values in the URL are ignored and the normal feed shows", async () => {
    nav.search = new URLSearchParams("lens=../../admin&merchant=Zepto&late=1&weekday=funday");
    const { asked, plain } = routes();
    renderWithQuery(<TransactionsPage />);
    expect(await screen.findByText("No transactions yet.")).toBeInTheDocument();
    expect(asked).toEqual([]);
    expect(plain()).toBe(1);
    expect(screen.queryByRole("list", { name: "Active filters" })).not.toBeInTheDocument();
  });

  it("an unknown weekday is dropped but the lens stays", async () => {
    nav.search = new URLSearchParams("lens=travel&weekday=funday&late=yes");
    const { asked } = routes();
    renderWithQuery(<TransactionsPage />);
    await screen.findByRole("button", { name: "Remove filter Lens: Travel" });
    expect(asked).toEqual(["?lens=travel&limit=1000"]);
  });

  it("a failing drill-down can be retried", async () => {
    nav.search = new URLSearchParams("lens=quick_commerce");
    let n = 0;
    routes(() => (n++ === 0 ? fail(500, "SERVER_001", "Down") : ok([], { cursor: null, hasMore: false, total: 0 })));
    const { user } = renderWithQuery(<TransactionsPage />);
    await user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText("No transactions match these filters.")).toBeInTheDocument();
  });
});
