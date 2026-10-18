import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { server } from "../../../../test/msw/server";
import { fail, ok } from "../../../../test/msw/envelope";
import { fx } from "../../../../test/fixtures";
import { nav } from "../../../../test/next";
import { renderWithQuery } from "../../../../test/render";
import TripDetailPage from "./page";

const summary = (over: object = {}) => ({
  id: "trip-1", name: "Trip, 24 Oct – 30 Oct 2026", named: false, start: "2026-10-24", end: "2026-10-30", total: "5882.00",
  transactionCount: 2, modes: ["flight"], legs: [], ...over,
});
const detail = (over: object = {}) => ({
  trip: summary(over),
  members: [
    { role: "booking", refunded: "0.00", transaction: fx.transaction({ id: "t-1", merchantName: "IndiGo", amount: "5432.00", category: "transport" }) },
    { role: "spend", refunded: "50.00", transaction: fx.transaction({ id: "t-2", merchantName: "Uber", amount: "450.00", category: "transport" }) },
  ],
});

describe("Trip detail (AOS-910)", () => {
  beforeEach(() => { nav.params = { id: "trip-1" }; });

  function routes() {
    const calls: string[] = [];
    server.use(
      http.get("/api/v1/trips/trip-1", () => ok(detail())),
      http.get("/api/v1/trips/trip-1/candidates", () => ok([fx.transaction({ id: "t-9", merchantName: "Sample Stay", amount: "3000.00" })])),
      http.patch("/api/v1/trips/trip-1", async ({ request }) => { calls.push(`rename ${JSON.stringify(await request.json())}`); return ok(summary({ name: "Home" })); }),
      http.post("/api/v1/trips/trip-1/transactions", async ({ request }) => { calls.push(`add ${JSON.stringify(await request.json())}`); return ok(summary()); }),
      http.delete("/api/v1/trips/trip-1/transactions/:t", ({ params }) => { calls.push(`remove ${params.t}`); return ok(summary()); }),
      http.delete("/api/v1/trips/trip-1", () => { calls.push("dismiss"); return ok({ id: "trip-1" }); }),
    );
    return calls;
  }

  it("shows the bookings and the spends around them, with one total", async () => {
    routes();
    renderWithQuery(<TripDetailPage />);
    expect(await screen.findByRole("heading", { name: "Trip, 24 Oct – 30 Oct 2026" })).toBeInTheDocument();
    expect(screen.getByLabelText("Trip total")).toHaveTextContent("₹5,882");
    const list = screen.getByRole("list", { name: "Payments in this trip" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(2);
    expect(within(list).getByText(/Booking ·/)).toBeInTheDocument();
    expect(within(list).getByText(/Around the trip · .* · ₹50 refunded/)).toBeInTheDocument();
  });

  it("rename, remove, add from nearby and not a trip", async () => {
    const calls = routes();
    const { user } = renderWithQuery(<TripDetailPage />);
    await user.click(await screen.findByRole("button", { name: "Rename" }));
    const input = screen.getByLabelText("Trip name");
    await user.clear(input);
    await user.type(input, "Home");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await user.click(await screen.findByRole("button", { name: "Remove Uber from this trip" }));
    await user.click(screen.getByRole("button", { name: "Add a payment" }));
    await user.click(await screen.findByRole("button", { name: "Add Sample Stay to this trip" }));
    await user.click(screen.getByRole("button", { name: "Not a trip" }));
    await waitFor(() => expect(calls).toEqual(['rename {"name":"Home"}', "remove t-2", 'add {"transactionId":"t-9"}', "dismiss"]));
    expect(nav.router.push).toHaveBeenCalledWith("/trips");
  });

  it("cancel keeps the name; a failed edit says why", async () => {
    routes();
    server.use(http.delete("/api/v1/trips/trip-1/transactions/:t", () => fail(404, "TRIP_002", "Transaction not found")));
    const { user } = renderWithQuery(<TripDetailPage />);
    await user.click(await screen.findByRole("button", { name: "Rename" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("heading", { name: "Trip, 24 Oct – 30 Oct 2026" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove Uber from this trip" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Transaction not found");
  });

  it("nearby payments: none", async () => {
    routes();
    server.use(http.get("/api/v1/trips/trip-1/candidates", () => ok([])));
    const { user } = renderWithQuery(<TripDetailPage />);
    await user.click(await screen.findByRole("button", { name: "Add a payment" }));
    expect(await screen.findByText("No other payments around these dates.")).toBeInTheDocument();
  });

  it("a trip that isn't yours or doesn't exist says so and can be retried", async () => {
    let calls = 0;
    server.use(http.get("/api/v1/trips/trip-1", () => (++calls === 1 ? fail(404, "TRIP_001", "Trip not found") : ok(detail()))));
    const { user } = renderWithQuery(<TripDetailPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Trip not found");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("heading", { name: /Trip, 24 Oct/ })).toBeInTheDocument();
  });

  it("an error loading nearby payments is shown", async () => {
    routes();
    server.use(http.get("/api/v1/trips/trip-1/candidates", () => fail(500, "SERVER_ERROR", "Server error")));
    const { user } = renderWithQuery(<TripDetailPage />);
    await user.click(await screen.findByRole("button", { name: "Add a payment" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Server error");
  });
});
