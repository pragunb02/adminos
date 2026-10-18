import { http } from "msw";
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { renderWithQuery } from "../../test/render";
import PayLaterGaps from "./PayLaterGaps";

const provider = (over: object = {}) => ({
  provider: "LazyPay", outstanding: "1200.00", nextDue: "2026-11-03", purchasesLast30Days: "200.00", unpaidBills: [],
  unbilledPurchases: { amount: "0.00", count: 0, transactionIds: [] },
  gaps: [{ billId: "b1", billAmount: "1200.00", dueDate: "2026-11-03", status: "upcoming", recorded: "200.00", unrecorded: "1000.00" }],
  ...over,
});

describe("Pay-later gaps in Review (AOS-909)", () => {
  it("says how much of a bill has no recorded purchases", async () => {
    server.use(http.get("/api/v1/pay-later", () => ok({ outstanding: "1200.00", currency: "INR", providers: [provider()] })));
    renderWithQuery(<PayLaterGaps />);
    const section = await screen.findByRole("region", { name: "Pay-later spending not in your totals" });
    expect(section).toHaveTextContent("LazyPay bill ₹1,200 (due 3 Nov): only ₹200 of purchases recorded, so ₹1,000 isn't in your spending.");
  });

  it("shows nothing when every bill is covered", async () => {
    server.use(http.get("/api/v1/pay-later", () => ok({ outstanding: "0.00", currency: "INR", providers: [provider({ gaps: [] })] })));
    const { container } = renderWithQuery(<PayLaterGaps />);
    await new Promise((r) => setTimeout(r, 50));
    expect(container).toBeEmptyDOMElement();
  });

  it("renders a provider name as text, never as markup", async () => {
    server.use(http.get("/api/v1/pay-later", () => ok({ outstanding: "1.00", currency: "INR", providers: [provider({ provider: "<img src=x onerror=alert(1)>" })] })));
    const { container } = renderWithQuery(<PayLaterGaps />);
    expect(await screen.findByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("an error says so and can be retried", async () => {
    let calls = 0;
    server.use(http.get("/api/v1/pay-later", () => (++calls === 1 ? fail(500, "SERVER_ERROR", "Server error") : ok({ outstanding: "0.00", currency: "INR", providers: [provider()] }))));
    const { user } = renderWithQuery(<PayLaterGaps />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Pay-later:");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("region", { name: "Pay-later spending not in your totals" })).toBeInTheDocument();
  });
});
