import { http, HttpResponse } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { ok } from "../../test/msw/envelope";
import { nav } from "../../test/next";
import { renderWithQuery } from "../../test/render";
import { DESTINATIONS, MORE_LINKS, Rail, TabBar, destinationOf } from "./Nav";
import AppShell from "./AppShell";
import Providers from "./Providers";
import MorePage from "@/app/more/page";
import SpendingPage from "@/app/spending/page";
import ReviewPage from "@/app/review/page";
import { api } from "@/lib/api";
import { render } from "@testing-library/react";

describe("destinations (D-49)", () => {
  it("are exactly five", () => {
    expect(DESTINATIONS.map((d) => d.label)).toEqual(["Home", "Spending", "Activity", "Review", "More"]);
  });
  it.each([
    ["/", "/"], ["/spending", "/spending"], ["/spending/category/food", "/spending"], ["/transactions", "/transactions"],
    ["/review", "/review"], ["/more", "/more"], ["/bills", "/more"], ["/settings", "/more"], ["/anomalies/a1", "/more"],
  ])("%s belongs to %s", (path, dest) => expect(destinationOf(path)).toBe(dest));
});

describe("TabBar (phones)", () => {
  it("shows 5 tabs and marks the current destination", () => {
    nav.pathname = "/bills";
    renderWithQuery(<TabBar />);
    const bar = screen.getByRole("navigation", { name: "Main navigation" });
    expect(within(bar).getAllByRole("link")).toHaveLength(5);
    expect(within(bar).getByRole("link", { name: /More/ })).toHaveAttribute("aria-current", "page");
    expect(within(bar).getByRole("link", { name: /Home/ })).not.toHaveAttribute("aria-current");
  });

  it("shows how many suggestions wait for review, capped at 99+", async () => {
    server.use(http.get("/api/v1/transactions/suggestions", () => ok(Array.from({ length: 3 }))));
    renderWithQuery(<TabBar />);
    expect(await screen.findByLabelText("3 to review")).toHaveTextContent("3");
  });

  it("caps the badge", async () => {
    server.use(http.get("/api/v1/transactions/suggestions", () => ok(Array.from({ length: 120 }))));
    renderWithQuery(<TabBar />);
    expect(await screen.findByLabelText("120 to review")).toHaveTextContent("99+");
  });

  it("no badge when nothing waits or the count can't load", async () => {
    server.use(http.get("/api/v1/transactions/suggestions", () => HttpResponse.json({ success: false, data: null, error: { code: "SERVER_001", message: "x" }, meta: {} }, { status: 500 })));
    renderWithQuery(<TabBar />);
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByLabelText(/to review/)).not.toBeInTheDocument();
  });
});

describe("Rail (desktop)", () => {
  it("has the 4 main destinations, More expanded, and Log out", async () => {
    nav.pathname = "/subscriptions";
    const logout = vi.spyOn(api, "logout").mockResolvedValue();
    const { user } = renderWithQuery(<Rail />);
    const rail = screen.getByRole("navigation", { name: "Main navigation" });
    for (const l of ["Home", "Spending", "Activity", "Review", ...MORE_LINKS.map((m) => m.label)]) {
      expect(within(rail).getByRole("link", { name: new RegExp(l) })).toBeInTheDocument();
    }
    expect(within(rail).getByRole("link", { name: "Subscriptions" })).toHaveAttribute("aria-current", "page");
    expect(within(rail).getByRole("link", { name: "Home" })).not.toHaveAttribute("aria-current");
    await user.click(within(rail).getByRole("button", { name: "Log out" }));
    expect(logout).toHaveBeenCalledOnce();
  });

  it("marks a main destination as current", () => {
    nav.pathname = "/transactions";
    renderWithQuery(<Rail />);
    expect(screen.getByRole("link", { name: "Activity" })).toHaveAttribute("aria-current", "page");
  });
});

describe("AppShell", () => {
  it("renders the page with both navigations available and the cash button", () => {
    renderWithQuery(<AppShell><p>Page body</p></AppShell>);
    expect(screen.getByText("Page body")).toBeInTheDocument();
    expect(screen.getAllByRole("navigation", { name: "Main navigation" })).toHaveLength(2); // one is hidden per breakpoint
    expect(screen.getByRole("button", { name: "Add cash spending" })).toBeInTheDocument();
  });
});

describe("More and placeholder pages", () => {
  it("More lists every secondary page and logs out", async () => {
    const logout = vi.spyOn(api, "logout").mockResolvedValue();
    const { user } = renderWithQuery(<MorePage />);
    const main = screen.getByRole("main");
    for (const m of MORE_LINKS) expect(within(main).getByRole("link", { name: new RegExp(m.label) })).toHaveAttribute("href", m.href);
    await user.click(within(main).getByRole("button", { name: "Log out" }));
    expect(logout).toHaveBeenCalledOnce();
  });

  it.each([[SpendingPage, "Spending"], [ReviewPage, "Review"]] as const)("%s placeholder says what is coming", (Page, title) => {
    renderWithQuery(<Page />);
    expect(screen.getByRole("heading", { level: 2, name: title })).toBeInTheDocument();
  });
});

describe("Providers", () => {
  it("gives children a query client", async () => {
    server.use(http.get("/api/v1/ping", () => ok("pong")));
    const { useQuery } = await import("@tanstack/react-query");
    const { getData } = await import("@/lib/query");
    function Ping() {
      const { data } = useQuery({ queryKey: ["ping"], queryFn: () => getData<string>("/api/v1/ping") });
      return <p>{data ?? "…"}</p>;
    }
    render(<Providers><Ping /></Providers>);
    expect(await screen.findByText("pong")).toBeInTheDocument();
  });
});
