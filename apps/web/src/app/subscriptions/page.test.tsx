import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import SubscriptionsPage from "./page";

describe("Subscriptions", () => {
  const lists: Record<string, unknown[]> = {
    "status=active": [fx.subscription(), fx.subscription({ id: "s-2", name: "Spotify", amount: "119", priceChanged: true, priceChangePct: 12, nextBillingDate: null })],
    "flagged=true": [fx.subscription({ id: "s-3", name: "Gym", isFlagged: true, flaggedReason: "No visits in 45 days", priceChanged: true, priceChangePct: -10 })],
    "status=cancelled": [],
  };
  const routes = () => server.use(
    http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
    http.get("/api/v1/subscriptions", ({ request }) => {
      const q = new URL(request.url).search.replace("?", "").split("&")[0];
      return ok(lists[q]);
    }),
  );

  it("summary, active list, price changes", async () => {
    routes();
    renderWithQuery(<SubscriptionsPage />);
    expect(await screen.findByText("₹1,847")).toBeInTheDocument();
    expect(screen.getByText("1 flagged")).toBeInTheDocument();
    expect(await screen.findByText("Spotify")).toBeInTheDocument();
    expect(screen.getByText("↑12%")).toBeInTheDocument();
    expect(screen.getByText(/· Next: 2026-10-25/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Active/ })).toHaveTextContent("(4)");
  });

  it("tabs switch the list: flagged shows the reason and actions; cancelled is empty", async () => {
    routes();
    const { user } = renderWithQuery(<SubscriptionsPage />);
    await user.click(screen.getByRole("button", { name: /Flagged/ }));
    expect(await screen.findByText("No visits in 45 days")).toBeInTheDocument();
    expect(screen.getByText("↓10%")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Cancelled/ }));
    expect(await screen.findByText("No cancelled subscriptions")).toBeInTheDocument();
  });

  it("price change without a percentage says changed; no summary hides counts", async () => {
    server.use(
      http.get("/api/v1/subscriptions/summary", () => fail(500, "SERVER_001", "x")),
      http.get("/api/v1/subscriptions", () => ok([fx.subscription({ priceChanged: true, priceChangePct: null })])),
    );
    renderWithQuery(<SubscriptionsPage />);
    expect(await screen.findByText("price changed")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Active" })).not.toHaveTextContent("(");
    expect(screen.queryByText("total monthly cost")).not.toBeInTheDocument();
  });

  it("no flagged count line when none are flagged", async () => {
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary({ flaggedCount: 0 }))),
      http.get("/api/v1/subscriptions", () => ok([])),
    );
    renderWithQuery(<SubscriptionsPage />);
    await screen.findByText("total monthly cost");
    expect(screen.queryByText(/flagged$/)).not.toBeInTheDocument();
  });

  it.each([["Cancel", "post", "/cancel"], ["Keep", "patch", "/dismiss"]] as const)("%s calls the API and refreshes", async (label, method, path) => {
    let flagged = [fx.subscription({ id: "s-3", name: "Gym", isFlagged: true })];
    const called: string[] = [];
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
      http.get("/api/v1/subscriptions", () => ok(flagged)),
      http[method](`/api/v1/subscriptions/s-3${path}`, () => { called.push(path); flagged = []; return ok({}); }),
    );
    const { user } = renderWithQuery(<SubscriptionsPage />);
    const card = (await screen.findByText("Gym")).closest(".rounded-lg") as HTMLElement;
    await user.click(within(card).getByRole("button", { name: label }));
    expect(await screen.findByText("No active subscriptions")).toBeInTheDocument();
    expect(called).toEqual([path]);
  });

  it("a failed action shows its error", async () => {
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
      http.get("/api/v1/subscriptions", () => ok([fx.subscription({ isFlagged: true })])),
      http.post("/api/v1/subscriptions/s-1/cancel", () => fail(500, "SERVER_001", "Could not cancel")),
    );
    const { user } = renderWithQuery(<SubscriptionsPage />);
    await user.click(await screen.findByRole("button", { name: "Cancel" }));
    expect(await screen.findByText("Could not cancel")).toBeInTheDocument();
  });

  it("list error, then retry", async () => {
    let n = 0;
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
      http.get("/api/v1/subscriptions", () => (++n === 1 ? fail(500, "SERVER_001", "List down") : ok([fx.subscription()]))),
    );
    const { user } = renderWithQuery(<SubscriptionsPage />);
    await user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Netflix")).toBeInTheDocument();
  });
});
