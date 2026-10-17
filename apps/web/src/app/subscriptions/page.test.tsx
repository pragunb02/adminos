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
    expect(await screen.findByText(/No visits in 45 days/)).toHaveTextContent("Why flagged: No visits in 45 days");
    expect(screen.getByText("↓10%")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Cancelled/ }));
    expect(await screen.findByText("No cancelled subscriptions")).toBeInTheDocument();
  });

  it("a scored flag says why with every waste reason, strongest first", async () => {
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
      http.get("/api/v1/subscriptions", () => ok([
        fx.subscription({
          isFlagged: true, flaggedReason: "old reason", wasteScore: 0.75,
          wasteReasons: ["Price went up 25% at the last charge", "You also pay for JioHotstar (both streaming)", "Usage unknown: AdminOS can't see whether you use it"],
        }),
        fx.subscription({ id: "s-2", name: "Spotify", wasteScore: 0.3, wasteReasons: ["Price went up 12% at the last charge"] }),
      ])),
    );
    renderWithQuery(<SubscriptionsPage />);
    const why = await screen.findByText(/Why flagged:/);
    expect(why.closest("p")).toHaveTextContent(
      "Why flagged: Price went up 25% at the last charge; You also pay for JioHotstar (both streaming); Usage unknown: AdminOS can't see whether you use it",
    );
    expect(screen.queryByText(/old reason/)).not.toBeInTheDocument();
    expect(screen.getAllByText(/Why flagged:/)).toHaveLength(1);
    expect(screen.queryByText(/12% at the last charge/)).not.toBeInTheDocument();
  });

  it("a flag with no reason at all shows no why line", async () => {
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
      http.get("/api/v1/subscriptions", () => ok([fx.subscription({ isFlagged: true })])),
    );
    renderWithQuery(<SubscriptionsPage />);
    expect(await screen.findByRole("button", { name: "Keep" })).toBeInTheDocument();
    expect(screen.queryByText(/Why flagged/)).not.toBeInTheDocument();
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

  it("Keep calls the API and refreshes", async () => {
    let flagged = [fx.subscription({ id: "s-3", name: "Gym", isFlagged: true })];
    const called: string[] = [];
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
      http.get("/api/v1/subscriptions", () => ok(flagged)),
      http.patch("/api/v1/subscriptions/s-3/dismiss", () => { called.push("/dismiss"); flagged = []; return ok({}); }),
    );
    const { user } = renderWithQuery(<SubscriptionsPage />);
    const card = (await screen.findByText("Gym")).closest(".rounded-lg") as HTMLElement;
    await user.click(within(card).getByRole("button", { name: "Keep" }));
    expect(await screen.findByText("No active subscriptions")).toBeInTheDocument();
    expect(called).toEqual(["/dismiss"]);
  });

  it("How to cancel shows the steps from the API in a dialog, and closes (AOS-513)", async () => {
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
      http.get("/api/v1/subscriptions", () => ok([fx.subscription({ id: "s-3", name: "Gym", isFlagged: true })])),
      http.post("/api/v1/subscriptions/s-3/cancel", () => ok({ subscriptionId: "s-3", via: "upi_autopay", steps: ["Open the UPI app you set it up in → AutoPay / Mandates.", "Find GYM and revoke the mandate."] })),
    );
    const { user } = renderWithQuery(<SubscriptionsPage />);
    await user.click(await screen.findByRole("button", { name: "How to cancel" }));
    const dialog = await screen.findByRole("dialog", { name: "How to cancel Gym" });
    expect(within(dialog).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "Open the UPI app you set it up in → AutoPay / Mandates.", "Find GYM and revoke the mandate.",
    ]);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "How to cancel" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("failures of Keep or How to cancel are shown", async () => {
    server.use(
      http.get("/api/v1/subscriptions/summary", () => ok(fx.subscriptionSummary())),
      http.get("/api/v1/subscriptions", () => ok([fx.subscription({ isFlagged: true })])),
      http.post("/api/v1/subscriptions/s-1/cancel", () => fail(500, "SERVER_001", "Could not load steps")),
      http.patch("/api/v1/subscriptions/s-1/dismiss", () => fail(500, "SERVER_001", "Could not keep")),
    );
    const { user } = renderWithQuery(<SubscriptionsPage />);
    await user.click(await screen.findByRole("button", { name: "How to cancel" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load steps");
    await user.click(screen.getByRole("button", { name: "Keep" }));
    expect(await screen.findByText("Could not keep")).toBeInTheDocument();
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
