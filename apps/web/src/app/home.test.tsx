import { http } from "msw";
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { fx } from "../../test/fixtures";
import { nav } from "../../test/next";
import { renderWithQuery } from "../../test/render";
import Home from "./page";

describe("Home (current dashboard; replaced by the console in AOS-303)", () => {
  it("shows loading, then greeting, summary and action items", async () => {
    server.use(http.get("/api/v1/dashboard/home", () => ok(fx.home())));
    renderWithQuery(<Home />);
    expect(screen.getByText("Loading dashboard...")).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Good morning, Pragun" })).toBeInTheDocument();
    expect(screen.getByText("₹12,500")).toBeInTheDocument();
    expect(screen.getByText("Open anomalies").previousSibling).toHaveClass("text-red-700");
    expect(screen.getAllByRole("heading", { level: 3, name: /Unusual charge|Unused Netflix|HDFC card bill|Weekly briefing/ })).toHaveLength(4);
  });

  it.each([
    ["Review", "/anomalies/an-1"],
    ["Review sub", "/subscriptions"],
    ["View bill", "/bills"],
    ["Read", "/briefings"],
  ])("action %s goes to %s", async (label, path) => {
    server.use(http.get("/api/v1/dashboard/home", () => ok(fx.home())));
    const { user } = renderWithQuery(<Home />);
    await user.click(await screen.findByRole("button", { name: label }));
    expect(nav.router.push).toHaveBeenCalledWith(path);
  });

  it("falls back to type-based severity and icons; unknown types still render", async () => {
    const home = fx.home();
    home.actionItems = [
      { ...home.actionItems[0], severity: undefined as never },
      { ...home.actionItems[1], severity: undefined as never },
      { ...home.actionItems[2], severity: undefined as never },
      { ...home.actionItems[3], severity: undefined as never },
      { ...home.actionItems[3], id: "a5", type: "other" as never, title: "Something else", severity: undefined as never },
    ];
    server.use(http.get("/api/v1/dashboard/home", () => ok(home)));
    const { user, container } = renderWithQuery(<Home />);
    await screen.findByText("Something else");
    expect(container.querySelectorAll(".border-l-red-400")).toHaveLength(1);
    expect(container.querySelectorAll(".border-l-amber-400")).toHaveLength(2);
    expect(screen.getByText("📌")).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "Read" })[1]);
    expect(nav.router.push).not.toHaveBeenCalled();
  });

  it("all clear when nothing needs attention; no briefing card without a briefing", async () => {
    server.use(http.get("/api/v1/dashboard/home", () => ok(fx.home({ actionItems: [], latestBriefing: null, summary: { ...fx.home().summary, openAnomalies: 0 } }))));
    renderWithQuery(<Home />);
    expect(await screen.findByText("All clear!")).toBeInTheDocument();
    expect(screen.queryByText("Latest Briefing")).not.toBeInTheDocument();
    expect(screen.getByText("Open anomalies").previousSibling).not.toHaveClass("text-red-700");
  });

  it("latest briefing links to briefings; total is optional", async () => {
    server.use(http.get("/api/v1/dashboard/home", () => ok(fx.home({ latestBriefing: { ...fx.home().latestBriefing!, totalSpent: null } }))));
    const { user } = renderWithQuery(<Home />);
    await user.click(await screen.findByRole("button", { name: "View all" }));
    expect(nav.router.push).toHaveBeenCalledWith("/briefings");
    expect(screen.queryByText(/Total spent/)).not.toBeInTheDocument();
  });

  it("shows the briefing's total when there is one", async () => {
    server.use(http.get("/api/v1/dashboard/home", () => ok(fx.home())));
    renderWithQuery(<Home />);
    expect(await screen.findByText("Total spent: ₹18,000")).toBeInTheDocument();
  });

  it("an error can be retried", async () => {
    let n = 0;
    server.use(http.get("/api/v1/dashboard/home", () => (++n === 1 ? fail(500, "SERVER_001", "Internal server error") : ok(fx.home()))));
    const { user } = renderWithQuery(<Home />);
    expect(await screen.findByText("Internal server error")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("heading", { name: "Good morning, Pragun" })).toBeInTheDocument();
  });
});
