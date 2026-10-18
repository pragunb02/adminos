import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import { modesLabel, tripDates } from "@/lib/trips";
import TripsPage from "./page";

const trip = (over: object = {}) => ({
  id: "trip-1", name: "Trip, 24 Oct – 30 Oct 2026", named: false, start: "2026-10-24", end: "2026-10-30", total: "11502.00",
  transactionCount: 8, modes: ["flight"], legs: [{ date: "2026-10-24", brand: "IndiGo", mode: "flight", transactionId: "t-1" }], ...over,
});

describe("Trips (AOS-910)", () => {
  it("shows the year's total and each trip with its dates, modes and total", async () => {
    server.use(http.get("/api/v1/trips", () => ok({ year: 2026, total: "12347.00", currency: "INR", trips: [trip(), trip({ id: "trip-2", name: "Home", named: true, modes: [], total: "845.00" })] })));
    renderWithQuery(<TripsPage />);
    expect(await screen.findByText("₹12,347")).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "Trips in 2026" });
    const first = within(list).getByRole("link", { name: /Trip, 24 Oct – 30 Oct 2026/ });
    expect(first).toHaveAttribute("href", "/trips/trip-1");
    expect(first).toHaveTextContent("24 Oct – 30 Oct · Flight · 8 payments");
    expect(within(list).getByRole("link", { name: /Home/ })).toHaveTextContent("Travel");
  });

  it("the year is in the URL and the arrows move it", async () => {
    nav.pathname = "/trips";
    nav.search = new URLSearchParams("year=2025");
    let asked = "";
    server.use(http.get("/api/v1/trips", ({ request }) => { asked = new URL(request.url).search; return ok({ year: 2025, total: "0.00", currency: "INR", trips: [] }); }));
    const { user } = renderWithQuery(<TripsPage />);
    expect(await screen.findByText(/No trips in 2025/)).toBeInTheDocument();
    expect(asked).toBe("?year=2025");
    await user.click(screen.getByRole("button", { name: "Trips in 2026" }));
    expect(nav.router.push).toHaveBeenCalledWith("/trips?year=2026", { scroll: false });
    await user.click(screen.getByRole("button", { name: "Trips in 2024" }));
    expect(nav.router.push).toHaveBeenCalledWith("/trips?year=2024", { scroll: false });
  });

  it("a malformed year in the URL is ignored", async () => {
    nav.search = new URLSearchParams("year=<script>");
    let asked = "x";
    server.use(http.get("/api/v1/trips", ({ request }) => { asked = new URL(request.url).search; return ok({ year: 2026, total: "0.00", currency: "INR", trips: [] }); }));
    renderWithQuery(<TripsPage />);
    expect(await screen.findByText(/No trips in 2026/)).toBeInTheDocument();
    expect(asked).toBe("");
  });

  it("an error says so and retries; an untrusted name stays text", async () => {
    let calls = 0;
    server.use(http.get("/api/v1/trips", () => (++calls === 1 ? fail(500, "SERVER_ERROR", "Server error")
      : ok({ year: 2026, total: "1.00", currency: "INR", trips: [trip({ name: "<img src=x onerror=alert(1)>" })] }))));
    const { user, container } = renderWithQuery(<TripsPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Server error");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("dates and modes read plainly", () => {
    expect(tripDates("2026-12-30", "2027-01-02")).toBe("30 Dec 2026 – 2 Jan 2027");
    expect(modesLabel(["flight", "train", "bus"])).toBe("Flight · Train · Bus");
  });
});
