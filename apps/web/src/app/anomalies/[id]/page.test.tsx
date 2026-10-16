import { http } from "msw";
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../../test/msw/server";
import { fail, ok } from "../../../../test/msw/envelope";
import { fx } from "../../../../test/fixtures";
import { nav } from "../../../../test/next";
import { renderWithQuery } from "../../../../test/render";
import AnomalyDetailPage from "./page";

const a1 = fx.anomaly();
const a2 = fx.anomaly({ id: "an-2", type: "odd_hours", reason: "At 3am", agentExplanation: null, confidenceScore: 0.71 });
const a3 = fx.anomaly({ id: "an-3", type: "mystery_kind", reason: "Hmm" });

describe("Anomaly review", () => {
  const routes = (open: unknown[], one: (id: string) => unknown = (id) => open.find((a) => (a as { id: string }).id === id)) => {
    server.use(
      http.get("/api/v1/anomalies", () => ok(open)),
      http.get("/api/v1/anomalies/:id", ({ params }) => { const a = one(params.id as string); return a ? ok(a) : fail(404, "NOT_FOUND", "Anomaly not found"); }),
    );
  };

  it("shows the requested anomaly with reason, AI explanation and the others to review", async () => {
    nav.params = { id: "an-1" };
    routes([a1, a2, a3]);
    renderWithQuery(<AnomalyDetailPage />);
    expect(screen.getByText("Loading anomalies...")).toBeInTheDocument();
    expect(await screen.findByText("3.4× your usual shopping spend")).toBeInTheDocument();
    expect(screen.getByText("This is much larger than usual.")).toBeInTheDocument();
    expect(screen.getByText("2 more to review")).toBeInTheDocument();
    expect(screen.getByText("Odd hours transaction")).toBeInTheDocument();
    expect(screen.getByText("mystery kind")).toBeInTheDocument();
  });

  it.each([
    ["foreign_currency", "Foreign currency charge"], ["card_testing", "Possible card testing"], ["duplicate_charge", "Possible duplicate charge"],
    ["large_amount", "Unusually large amount"],
  ])("type %s is labelled %s", async (type, label) => {
    nav.params = { id: "x" };
    routes([fx.anomaly({ id: "x", type })]);
    renderWithQuery(<AnomalyDetailPage />);
    expect(await screen.findByText(label)).toBeInTheDocument();
  });

  it("picking another switches to it; no AI explanation is shown when absent", async () => {
    nav.params = { id: "an-1" };
    routes([a1, a2]);
    const { user } = renderWithQuery(<AnomalyDetailPage />);
    await user.click(await screen.findByRole("button", { name: /Odd hours transaction/ }));
    expect(await screen.findByText("At 3am", { selector: "p.text-sm" })).toBeInTheDocument();
    expect(screen.queryByText("AI Explanation")).not.toBeInTheDocument();
  });

  it("an id that can't be loaded falls back to the first open anomaly", async () => {
    nav.params = { id: "gone" };
    routes([a2]);
    renderWithQuery(<AnomalyDetailPage />);
    expect(await screen.findByText("At 3am", { selector: "p.text-sm" })).toBeInTheDocument();
  });

  it("resolving moves to the next one; the last one returns home", async () => {
    nav.params = { id: "an-1" };
    let open = [a1, a2];
    const statuses: string[] = [];
    server.use(
      http.get("/api/v1/anomalies", () => ok(open)),
      http.get("/api/v1/anomalies/:id", ({ params }) => ok(open.find((a) => a.id === params.id) ?? fx.anomaly({ id: params.id as string, status: "confirmed_safe" }))),
      http.patch("/api/v1/anomalies/:id", async ({ params, request }) => {
        statuses.push(((await request.json()) as { status: string }).status);
        open = open.filter((a) => a.id !== params.id);
        return ok({});
      }),
    );
    const { user } = renderWithQuery(<AnomalyDetailPage />);
    await user.click(await screen.findByRole("button", { name: "✓ Confirmed Safe" }));
    expect(await screen.findByText("At 3am", { selector: "p.text-sm" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "✕ Confirmed Fraud" }));
    await screen.findByText("At 3am", { selector: "p.text-sm" });
    await new Promise((r) => setTimeout(r, 50));
    expect(nav.router.push).toHaveBeenCalledWith("/");
    expect(statuses).toEqual(["confirmed_safe", "confirmed_fraud"]);
  });

  it("a resolved anomaly shows no action buttons", async () => {
    nav.params = { id: "done" };
    routes([], () => fx.anomaly({ id: "done", status: "confirmed_safe" }));
    renderWithQuery(<AnomalyDetailPage />);
    await screen.findByText("3.4× your usual shopping spend");
    expect(screen.queryByRole("button", { name: /Confirmed/ })).not.toBeInTheDocument();
  });

  it("a failed resolve shows the error and keeps the anomaly", async () => {
    nav.params = { id: "an-1" };
    routes([a1]);
    server.use(http.patch("/api/v1/anomalies/:id", () => fail(409, "CONFLICT", "Already resolved")));
    const { user } = renderWithQuery(<AnomalyDetailPage />);
    await user.click(await screen.findByRole("button", { name: "✓ Confirmed Safe" }));
    expect(await screen.findByText("Already resolved")).toBeInTheDocument();
    expect(nav.router.push).not.toHaveBeenCalled();
  });

  it("nothing open: all reviewed", async () => {
    nav.params = { id: "x" };
    routes([]);
    renderWithQuery(<AnomalyDetailPage />);
    expect(await screen.findByText("No open anomalies")).toBeInTheDocument();
  });

  it("list and item both failing is an error that can be retried; back goes home", async () => {
    nav.params = { id: "an-1" };
    let n = 0;
    server.use(
      http.get("/api/v1/anomalies", () => (++n === 1 ? fail(500, "SERVER_001", "Down") : ok([a1]))),
      http.get("/api/v1/anomalies/:id", () => (n <= 1 ? fail(500, "SERVER_001", "Down") : ok(a1))),
    );
    const { user } = renderWithQuery(<AnomalyDetailPage />);
    expect(await screen.findByText("Down")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("3.4× your usual shopping spend")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "← Back" }));
    expect(nav.router.push).toHaveBeenCalledWith("/");
  });
});
