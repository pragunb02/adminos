import { http } from "msw";
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import BriefingsPage from "./page";

const insight = (id: string, actionType: string, entityId: string | null = "e-1") =>
  ({ id, type: "tip", title: `Insight ${id}`, body: "Body", severity: "info", actionType, entityId, status: "open" });

describe("Briefings", () => {
  const routes = (latest: unknown, history: unknown[], latestFails = false, historyFails = false) =>
    server.use(
      http.get("/api/v1/briefings/latest", () => (latestFails ? fail(404, "NOT_FOUND", "No briefing") : ok(latest))),
      http.get("/api/v1/briefings", () => (historyFails ? fail(500, "SERVER_001", "History down") : ok(history))),
    );

  it("shows the latest expanded, with stats, categories, and the rest as history", async () => {
    const latest = fx.briefing({ insights: [insight("1", "cancel_sub")] });
    routes(latest, [latest, fx.briefing({ id: "br-0", periodStart: "2026-09-28", periodEnd: "2026-10-04", content: "Older", totalSpent: "9000" }),
      fx.briefing({ id: "br-x", totalSpent: null, content: "No total" })]);
    renderWithQuery(<BriefingsPage />);
    expect(screen.getByText("Loading briefings...")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Latest Briefing" })).toBeInTheDocument();
    expect(screen.getByText("₹18,000")).toBeInTheDocument();
    expect(screen.getByText("₹1,00,000")).toBeInTheDocument();
    expect(screen.getByText("food: ₹6,000")).toBeInTheDocument();
    expect(screen.getByText("Older")).toBeInTheDocument();
    expect(screen.getByText("₹9,000")).toBeInTheDocument();
    expect(screen.getAllByText("You spent ₹18,000 this week.")).toHaveLength(1);
  });

  it.each([
    ["cancel_sub", "Review sub", "/subscriptions"],
    ["review_anomaly", "Review", "/anomalies/e-1"],
    ["pay_bill", "View bill", "/bills"],
  ])("insight %s → %s → %s", async (actionType, label, path) => {
    routes(fx.briefing({ insights: [insight("1", actionType)] }), []);
    const { user } = renderWithQuery(<BriefingsPage />);
    await user.click(await screen.findByRole("button", { name: label }));
    expect(nav.router.push).toHaveBeenCalledWith(path);
  });

  it("unknown actions say View and go nowhere; none has no button; no entity does nothing", async () => {
    routes(fx.briefing({ insights: [insight("1", "something"), insight("2", "none"), insight("3", "pay_bill", null)] }), []);
    const { user } = renderWithQuery(<BriefingsPage />);
    await user.click(await screen.findByRole("button", { name: "View" }));
    await user.click(screen.getByRole("button", { name: "View bill" }));
    expect(nav.router.push).not.toHaveBeenCalled();
    expect(screen.getByText("Insight 2").closest(".rounded-lg")!.querySelector("button")).toBeNull();
  });

  it("no stats or categories when missing", async () => {
    routes(fx.briefing({ totalSpent: null, totalIncome: null, topCategories: null, insights: null }), []);
    renderWithQuery(<BriefingsPage />);
    await screen.findByRole("heading", { name: "Latest Briefing" });
    expect(screen.queryByText("Spent:")).not.toBeInTheDocument();
    expect(screen.queryByText("Top spending")).not.toBeInTheDocument();
    expect(screen.queryByText("Action items")).not.toBeInTheDocument();
  });

  it("empty categories hide the section", async () => {
    routes(fx.briefing({ topCategories: [] }), []);
    renderWithQuery(<BriefingsPage />);
    await screen.findByRole("heading", { name: "Latest Briefing" });
    expect(screen.queryByText("Top spending")).not.toBeInTheDocument();
  });

  it("no latest briefing is not an error: history still shows", async () => {
    routes(null, [fx.briefing({ id: "old", content: "Old one" })], true);
    renderWithQuery(<BriefingsPage />);
    expect(await screen.findByText("Old one")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Latest Briefing" })).not.toBeInTheDocument();
  });

  it("nothing at all: the empty message", async () => {
    routes(null, [], true);
    renderWithQuery(<BriefingsPage />);
    expect(await screen.findByText(/No briefings yet/)).toBeInTheDocument();
  });

  it("history failing with no latest is an error that can be retried", async () => {
    let n = 0;
    server.use(
      http.get("/api/v1/briefings/latest", () => fail(404, "NOT_FOUND", "none")),
      http.get("/api/v1/briefings", () => (++n === 1 ? fail(500, "SERVER_001", "History down") : ok([fx.briefing({ content: "Back" })]))),
    );
    const { user } = renderWithQuery(<BriefingsPage />);
    expect(await screen.findByText("History down")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Back")).toBeInTheDocument();
  });

  it("history failing with a latest still shows the latest", async () => {
    routes(fx.briefing(), [], false, true);
    renderWithQuery(<BriefingsPage />);
    expect(await screen.findByRole("heading", { name: "Latest Briefing" })).toBeInTheDocument();
    expect(screen.queryByText("History down")).not.toBeInTheDocument();
  });
});
