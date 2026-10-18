import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import { wx } from "../../../test/wealthFixtures";
import WealthPage from "./page";

function routes(holdings: () => Response = () => ok(wx.overview()), flows: () => Response = () => ok(wx.flows())) {
  server.use(http.get("/api/v1/wealth/holdings", holdings), http.get("/api/v1/wealth/investment-flows", flows));
}

describe("Wealth (AOS-820)", () => {
  beforeEach(() => { nav.pathname = "/wealth"; });

  it("shows the total, the gain in words, the return basis and the price date", async () => {
    routes();
    renderWithQuery(<WealthPage />);
    const total = await screen.findByRole("region", { name: "Your mutual funds" });
    expect(total).toHaveTextContent("₹18,000");
    expect(total).toHaveTextContent("Up ₹3,000 (+20.0%)");
    expect(total).toHaveTextContent("+17.5% so far (under a year)");
    expect(total).toHaveTextContent("Prices as of 31 Dec 2025");
    expect(screen.getByRole("link", { name: "Import a statement" })).toHaveAttribute("href", "/wealth/import");
  });

  it("lists holdings by asset class with How we got this", async () => {
    routes();
    const { user } = renderWithQuery(<WealthPage />);
    const group = await screen.findByRole("region", { name: "Indian equity · ₹18,000" });
    const h = within(group).getByRole("article", { name: "Example Flexi Cap Fund - Direct Plan - Growth" });
    expect(h).toHaveTextContent("Folio ••••4/56 · Direct plan");
    expect(h).toHaveTextContent("NAV ₹120 on 31 Dec 2025");
    await user.click(within(h).getByText("How we got this"));
    expect(within(h).getByText("Value = units × price = 18000.00")).toBeVisible();
  });

  it("labels stale prices, cost valuations, losses, regular plans and issues", async () => {
    routes(() => ok(wx.overview({
      stale: 1, valuedAtCost: 1, gain: "-500.00", gainPercent: "-3.3",
      groups: [{ assetClass: "debt", value: "15000.00", invested: "15500.00", holdings: [
        wx.holding({ scheme: "Stale fund", plan: "regular", how: { ...wx.holding().how, stale: true }, issues: ["Some units arrived without a cost"] }),
        wx.holding({ scheme: "New fund", plan: null, isin: null, valuedAtCost: true, gain: "0.00", gainPercent: "0.0", ret: { basis: "none", percent: null, days: 0, reason: "Not enough transactions to compute a return" } }),
      ] }],
    })));
    renderWithQuery(<WealthPage />);
    const total = await screen.findByRole("region", { name: "Your mutual funds" });
    expect(total).toHaveTextContent("Down ₹500 (−3.3%)");
    expect(total).toHaveTextContent("1 stale · 1 valued at cost");
    const stale = screen.getByRole("article", { name: "Stale fund" });
    expect(stale).toHaveTextContent("stale"); expect(stale).toHaveTextContent("Regular plan"); expect(stale).toHaveTextContent("Some units arrived without a cost");
    const fresh = screen.getByRole("article", { name: "New fund" });
    expect(fresh).toHaveTextContent("No price yet: valued at cost"); expect(fresh).toHaveTextContent("No change");
    expect(fresh).toHaveTextContent("Not enough transactions to compute a return");
  });

  it("holdings that don't add up wait in Needs review", async () => {
    routes(() => ok(wx.overview({ needsReview: [wx.blocked()] })));
    renderWithQuery(<WealthPage />);
    const review = await screen.findByRole("region", { name: "Needs review (1)" });
    expect(within(review).getByRole("listitem", { name: "Example Liquid Fund - Regular Plan - Growth needs review" })).toHaveTextContent("statement says 11 units");
  });

  it("an empty wealth page points to the import", async () => {
    routes(() => ok({ ...wx.empty(), pricesAsOf: null }));
    renderWithQuery(<WealthPage />);
    expect(await screen.findByRole("link", { name: "Import your CAMS or KFintech statement" })).toHaveAttribute("href", "/wealth/import");
  });

  it("no prices yet says so", async () => {
    routes(() => ok(wx.overview({ pricesAsOf: null })));
    renderWithQuery(<WealthPage />);
    expect(await screen.findByText("No prices yet")).toBeInTheDocument();
  });

  it("a failure can be retried", async () => {
    let calls = 0;
    routes(() => (++calls === 1 ? fail(503, "SERVER_ERROR", "Wealth is unavailable") : ok(wx.overview())));
    const { user } = renderWithQuery(<WealthPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Wealth is unavailable");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("region", { name: "Your mutual funds" })).toBeInTheDocument();
  });

  it("money in and out of investments is summarised, with what didn't match", async () => {
    routes();
    renderWithQuery(<WealthPage />);
    const flows = await screen.findByRole("region", { name: "Money in and out of investments" });
    expect(await within(flows).findByText(/paid ₹10,000 into investments and received ₹5,500 back/)).toBeInTheDocument();
    expect(flows).toHaveTextContent("1 bank payment didn't match");
  });

  it("flows: several unmatched are plural, none says nothing, an error shows in the card", async () => {
    routes(undefined, () => ok(wx.flows({ unmatched: 2 })));
    const first = renderWithQuery(<WealthPage />);
    expect(await screen.findByText(/2 bank payments didn't match/)).toBeInTheDocument();
    first.unmount();
    routes(undefined, () => fail(503, "SERVER_ERROR", "Flows are down"));
    renderWithQuery(<WealthPage />);
    const flows = await screen.findByRole("region", { name: "Money in and out of investments" });
    expect(await within(flows).findByRole("alert")).toHaveTextContent("Flows are down");
  });

  it("scheme names from statements render as text", async () => {
    const hostile = `<img src=x onerror="window.__pwned=1">`;
    routes(() => ok(wx.overview({ groups: [{ assetClass: "other", value: "1.00", invested: "1.00", holdings: [wx.holding({ scheme: hostile, amc: hostile })] }] })));
    const { container } = renderWithQuery(<WealthPage />);
    expect(await screen.findByRole("article", { name: hostile })).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });
});
