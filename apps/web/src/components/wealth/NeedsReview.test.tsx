import { http } from "msw";
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { renderWithQuery } from "../../../test/render";
import { wx } from "../../../test/wealthFixtures";
import WealthReviewSection from "./NeedsReview";

describe("Review: investments that don't add up (AOS-820)", () => {
  it("shows nothing while every holding adds up", async () => {
    server.use(http.get("/api/v1/wealth/reconciliation", () => ok({ holdings: [{ ...wx.blocked(), status: "reconciled", reason: null }], blocked: 0 })));
    const { container } = renderWithQuery(<WealthReviewSection />);
    await new Promise((r) => setTimeout(r, 50));
    expect(container).toBeEmptyDOMElement();
  });

  it("lists blocked holdings with the reason and a way to fix them", async () => {
    server.use(http.get("/api/v1/wealth/reconciliation", () => ok({ holdings: [wx.blocked()], blocked: 1 })));
    renderWithQuery(<WealthReviewSection />);
    const section = await screen.findByRole("region", { name: "Investments that don't add up" });
    expect(section).toHaveTextContent("(1)");
    expect(section).toHaveTextContent("Folio ••••5678 · statement of 31 Dec 2025");
    expect(section).toHaveTextContent("Statement: 11 units · your transactions: 10 units");
    expect(screen.getByRole("link", { name: "Import another statement" })).toHaveAttribute("href", "/wealth/import");
  });

  it("a failure can be retried", async () => {
    let calls = 0;
    server.use(http.get("/api/v1/wealth/reconciliation", () => (++calls === 1 ? fail(503, "SERVER_ERROR", "Down") : ok({ holdings: [wx.blocked()], blocked: 1 }))));
    const { user } = renderWithQuery(<WealthReviewSection />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Investments to review couldn't load: Down");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("region", { name: "Investments that don't add up" })).toBeInTheDocument();
  });
});
