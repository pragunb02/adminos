import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { cx } from "../../../test/coachFixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import LeaksPage from "./page";

function freezeIndiaSunday() {
  vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-18T06:30:00Z"));
  vi.spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions").mockReturnValue({ timeZone: "Asia/Kolkata" } as Intl.ResolvedDateTimeFormatOptions);
}

/** The leaks and lenses endpoints, recording what the page asked and sent. */
function routes(over: { leaks?: () => Response; lenses?: () => Response; put?: () => Response; del?: () => Response } = {}) {
  const asked: string[] = [];
  const puts: { lens: string; body: unknown }[] = [];
  const deletes: string[] = [];
  server.use(
    http.get("/api/v1/coach/leaks", ({ request }) => { asked.push(new URL(request.url).search); return over.leaks?.() ?? ok(cx.leaks()); }),
    http.get("/api/v1/coach/lenses", () => over.lenses?.() ?? ok(cx.lenses())),
    http.put("/api/v1/coach/lenses/:lens/merchants", async ({ request, params }) => {
      puts.push({ lens: String(params.lens), body: await request.json() });
      return over.put?.() ?? ok({ lens: params.lens, merchant: "x", include: false });
    }),
    http.delete("/api/v1/coach/lenses/:lens/merchants", ({ request, params }) => {
      deletes.push(`${params.lens}${new URL(request.url).search}`);
      return over.del?.() ?? ok({ lens: params.lens, merchant: "x", cleared: true });
    }),
  );
  return { asked, puts, deletes };
}

describe("Where it leaks (AOS-902)", () => {
  beforeEach(() => { freezeIndiaSunday(); nav.pathname = "/leaks"; });
  afterEach(() => vi.useRealTimers());

  it("shows each lens with its numbers, every number linking to its transactions", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    const { asked } = routes();
    renderWithQuery(<LeaksPage />);
    const qc = await screen.findByRole("region", { name: "Quick commerce" });
    expect(asked).toEqual(["?from=2026-10-12&to=2026-10-18"]);
    expect(within(qc).getByRole("link", { name: /Quick commerce: ₹1,200 spent/ })).toHaveAttribute("href", "/transactions?lens=quick_commerce&from=2026-10-12&to=2026-10-18");
    expect(within(qc).getByRole("link", { name: /3 orders/ })).toHaveAttribute("href", "/transactions?lens=quick_commerce&from=2026-10-12&to=2026-10-18");
    expect(within(qc).getByText("₹433.33")).toBeInTheDocument();
    expect(within(qc).getByRole("link", { name: /late-night orders/ })).toHaveAttribute("href", "/transactions?lens=quick_commerce&from=2026-10-12&to=2026-10-18&late=1");
    expect(within(qc).getByText("2 of 3 orders after 22:00 (67%)")).toBeInTheDocument();
    expect(within(qc).getByRole("link", { name: /₹500 the period before/ })).toHaveAttribute("href", "/transactions?lens=quick_commerce&from=2026-10-05&to=2026-10-11");
    expect(within(qc).getByText(/▲ 140% more than before/)).toBeInTheDocument();
    expect(within(qc).getByRole("link", { name: "Zepto" })).toHaveAttribute("href", "/transactions?lens=quick_commerce&from=2026-10-12&to=2026-10-18&merchant=Zepto");
    expect(within(qc).getByText(/2 orders · ₹500 each · 1 late/)).toBeInTheDocument();
    expect(within(qc).getByRole("img", { name: /most on Tue \(₹600, 1 order\)/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /All leaks: ₹951/ })).toHaveAttribute("href", "/transactions?lens=all&from=2026-10-12&to=2026-10-18");
    expect(screen.getByText("2026-10-12 → 2026-10-18")).toBeInTheDocument();

    const shopping = screen.getByRole("region", { name: "Shopping" });
    expect(within(shopping).getByText("(refunds only)")).toBeInTheDocument();
    expect(within(shopping).getByText("—")).toBeInTheDocument();
    const food = screen.getByRole("region", { name: "Food delivery" });
    expect(within(food).getByText("0 of 1 order after 22:00 (0%)")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Travel" })).getByText("Nothing in this period.")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Travel" })).getByText("No orders")).toBeInTheDocument();
  });

  it("the weekday table lists each day with a link", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    routes();
    const { user } = renderWithQuery(<LeaksPage />);
    const qc = await screen.findByRole("region", { name: "Quick commerce" });
    await user.click(within(qc).getByRole("button", { name: "View days as table" }));
    const table = within(qc).getByRole("table", { name: "Quick commerce by weekday" });
    expect(within(table).getByRole("link", { name: "Tue" })).toHaveAttribute("href", "/transactions?lens=quick_commerce&from=2026-10-12&to=2026-10-18&weekday=tue");
    expect(within(table).getByText("−₹100")).toBeInTheDocument();
    await user.click(within(qc).getByRole("button", { name: "View as chart" }));
    expect(within(qc).getByRole("img")).toBeInTheDocument();
  });

  it("uses the console period by default and switches to a custom range", async () => {
    const { asked } = routes();
    const { user } = renderWithQuery(<LeaksPage />);
    await screen.findByRole("region", { name: "Quick commerce" });
    expect(asked[0]).toBe("?from=2026-10-01&to=2026-10-18");
    expect(screen.getByRole("heading", { name: "October 2026" })).toBeInTheDocument();
    await user.click(screen.getByText("Custom range"));
    const from = screen.getByLabelText("From date");
    await user.clear(from);
    await user.type(from, "2026-09-01");
    await user.click(screen.getByRole("button", { name: "Show" }));
    expect(nav.router.push).toHaveBeenCalledWith("/leaks?from=2026-09-01&to=2026-10-18", { scroll: false });
  });

  it("a custom range goes back to periods", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18&p=week");
    routes();
    const { user } = renderWithQuery(<LeaksPage />);
    await user.click(await screen.findByRole("button", { name: "Back to periods" }));
    expect(nav.router.push).toHaveBeenCalledWith("/leaks?p=week", { scroll: false });
  });

  it("a bad custom range shows a notice and the month", async () => {
    nav.search = new URLSearchParams("from=2026-10-10&to=2026-10-01");
    const { asked } = routes();
    renderWithQuery(<LeaksPage />);
    expect(await screen.findByRole("status")).toHaveTextContent("Showing the period instead: the start is after the end.");
    await screen.findByRole("region", { name: "Quick commerce" });
    expect(asked[0]).toBe("?from=2026-10-01&to=2026-10-18");
  });

  it("an empty period says so", async () => {
    routes({ leaks: () => ok(cx.emptyLeaks()) });
    renderWithQuery(<LeaksPage />);
    expect(await screen.findByText(/No leaks in this period/)).toBeInTheDocument();
  });

  it("an error shows a retry that works", async () => {
    let n = 0;
    routes({ leaks: () => (n++ === 0 ? fail(500, "SERVER_001", "Leaks are down") : ok(cx.leaks())) });
    const { user } = renderWithQuery(<LeaksPage />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Leaks are down");
    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("region", { name: "Quick commerce" })).toBeInTheDocument();
  });

  it("Not a leak takes the merchant out, with undo", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    const { puts, deletes } = routes();
    const { user } = renderWithQuery(<LeaksPage />);
    const qc = await screen.findByRole("region", { name: "Quick commerce" });
    await user.click(within(qc).getByRole("button", { name: "Not a leak: take Zepto out of Quick commerce" }));
    expect(await screen.findByText("Zepto is no longer counted in Quick commerce")).toBeInTheDocument();
    expect(puts).toEqual([{ lens: "quick_commerce", body: { merchant: "Zepto", include: false } }]);
    await user.click(screen.getByRole("button", { name: "Undo" }));
    await waitFor(() => expect(deletes).toEqual(["quick_commerce?merchant=Zepto"]));
    await waitFor(() => expect(screen.queryByText("Zepto is no longer counted in Quick commerce")).not.toBeInTheDocument());
  });

  it("adds a merchant, and puts back or removes the owner's choices", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    const { puts, deletes } = routes();
    const { user } = renderWithQuery(<LeaksPage />);
    const nights = await screen.findByRole("region", { name: "Nights out" });
    await user.click(within(nights).getByText("Edit Nights out"));
    expect(await within(nights).findByText(/names with bar, pub, club…/)).toBeInTheDocument();
    const add = within(nights).getByRole("button", { name: "Add" });
    expect(add).toBeDisabled();
    await user.type(within(nights).getByLabelText("Merchant to add to Nights out"), "  Skyye Lounge ");
    await user.click(add);
    await waitFor(() => expect(puts).toEqual([{ lens: "nights_out", body: { merchant: "Skyye Lounge", include: true } }]));
    await user.click(within(nights).getByRole("button", { name: "Remove Rohit Sharma (Nights out)" }));
    const qc = screen.getByRole("region", { name: "Quick commerce" });
    await user.click(within(qc).getByText("Edit Quick commerce"));
    await user.click(within(qc).getByRole("button", { name: "Put back Dunzo (Quick commerce)" }));
    await waitFor(() => expect(deletes).toEqual(["nights_out?merchant=Rohit+Sharma", "quick_commerce?merchant=Dunzo"]));
  });

  it("a failed change is shown", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    routes({ put: () => fail(404, "COACH_001", "Lens not found") });
    const { user } = renderWithQuery(<LeaksPage />);
    const qc = await screen.findByRole("region", { name: "Quick commerce" });
    await user.click(within(qc).getByRole("button", { name: "Not a leak: take Blinkit out of Quick commerce" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Lens not found");
  });

  it("renders merchant names as text, never as HTML", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    const hostile = "<img src=x onerror=alert(1)>";
    const data = cx.leaks();
    data.lenses[0].merchants[0] = { ...data.lenses[0].merchants[0], merchant: hostile };
    routes({ leaks: () => ok(data) });
    const { container } = renderWithQuery(<LeaksPage />);
    expect(await screen.findByRole("link", { name: hostile })).toBeInTheDocument();
    expect(container.querySelector("img[src='x']")).toBeNull();
  });

  // AOS-913: the pay-later lens is a payment-method rule (AOS-912).
  it("the Pay later card says it counts purchases paid with pay later", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    routes();
    const { user } = renderWithQuery(<LeaksPage />);
    const payLater = await screen.findByRole("region", { name: "Pay later" });
    await user.click(within(payLater).getByText("Edit Pay later"));
    expect(await within(payLater).findByText(/Counts purchases paid with pay later\./)).toBeInTheDocument();
    expect(within(payLater).queryByText(/only the merchants you add/)).toBeNull();
  });

  it("a lens list from an API without payment methods still reads", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    const data = cx.lenses();
    data.lenses = data.lenses.map((l) => {
      const old = { ...l, brands: l.id === "pay_later" ? ["LazyPay"] : l.brands };
      delete old.paymentMethods;
      return old;
    });
    routes({ lenses: () => ok(data) });
    const { user } = renderWithQuery(<LeaksPage />);
    const payLater = await screen.findByRole("region", { name: "Pay later" });
    await user.click(within(payLater).getByText("Edit Pay later"));
    expect(await within(payLater).findByText(/Counts 1 app or shop\./)).toBeInTheDocument();
  });

  it("renders a hostile payment method name as text, never as HTML", async () => {
    nav.search = new URLSearchParams("from=2026-10-12&to=2026-10-18");
    const hostile = "<img src=x onerror=alert(1)>";
    const data = cx.lenses();
    data.lenses = data.lenses.map((l) => (l.id === "pay_later" ? { ...l, paymentMethods: [hostile] } : l));
    routes({ lenses: () => ok(data) });
    const { user, container } = renderWithQuery(<LeaksPage />);
    const payLater = await screen.findByRole("region", { name: "Pay later" });
    await user.click(within(payLater).getByText("Edit Pay later"));
    expect(await within(payLater).findByText(/Counts purchases paid with <img src=x onerror=alert\(1\)>\./)).toBeInTheDocument();
    expect(container.querySelector("img[src='x']")).toBeNull();
  });

  it("explains how the numbers are made", async () => {
    routes();
    renderWithQuery(<LeaksPage />);
    expect(await screen.findByText("How we got this")).toBeInTheDocument();
    expect(screen.getByText(/Late night is 22:00–05:00 in your time zone/)).toBeInTheDocument();
    expect(screen.getByText(/UPI payments to people/)).toBeInTheDocument();
  });
});
