import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { fx } from "../../test/fixtures";
import { renderWithQuery } from "../../test/render";
import Mandates from "./Mandates";

describe("AutoPay & mandates (AOS-502)", () => {
  it("lists each mandate with its rail, frequency, limit, next and last debit", async () => {
    server.use(http.get("/api/v1/mandates", () => ok([fx.mandate()])))
    renderWithQuery(<Mandates />);
    const m = await screen.findByRole("listitem", { name: "BAJAJ FINANCE LTD" });
    expect(within(m).getByText("e-NACH · monthly · up to ₹5,400")).toBeInTheDocument();
    expect(within(m).getByText("Active")).toBeInTheDocument();
    expect(m).toHaveTextContent("Next: 5 Nov 2026 · ₹5,400.");
    expect(m).toHaveTextContent("Last taken 5 Oct 2026 · ₹5,400.");
    expect(m).toHaveTextContent("Valid until 5 Mar 2030.");
    expect(within(m).queryByText(/still needed/)).not.toBeInTheDocument();
  });

  it("flags unused mandates and shows other states plainly", async () => {
    server.use(http.get("/api/v1/mandates", () => ok([
      fx.mandate({ id: "a", payee: "Cult.fit", rail: "upi_autopay", frequency: "as_presented", amount: null, dormant: true, nextDebitOn: null, lastDebitAt: null, validUntil: null }),
      fx.mandate({ id: "b", payee: "Old card SI", rail: "card_si", frequency: "unknown", status: "revoked", nextDebitOn: null, nextDebitAmount: null }),
      fx.mandate({ id: "c", payee: "Mystery", rail: "unknown", status: "expired", nextDebitAmount: null, lastDebitAmount: null }),
    ])));
    renderWithQuery(<Mandates />);
    const cult = await screen.findByRole("listitem", { name: "Cult.fit" });
    expect(within(cult).getByText("UPI AutoPay · when billed")).toBeInTheDocument();
    expect(within(cult).getByText(/still needed\? Revoke it in your UPI app or bank/)).toBeInTheDocument();
    const old = screen.getByRole("listitem", { name: "Old card SI" });
    expect(within(old).getByText("Card standing instruction · up to ₹5,400")).toBeInTheDocument();
    expect(within(old).getByText("Revoked")).toBeInTheDocument();
    expect(old).not.toHaveTextContent("Valid until");
    expect(screen.getByRole("listitem", { name: "Mystery" })).toHaveTextContent("Expired");
  });

  it("explains when there are none, and a load error can be retried", async () => {
    let broken = true;
    server.use(http.get("/api/v1/mandates", () => (broken ? fail(500, "SERVER_001", "Internal server error") : ok([]))));
    const { user } = renderWithQuery(<Mandates />);
    expect(screen.getByText("Loading mandates…")).toBeInTheDocument();
    const retry = await screen.findByRole("button", { name: "Try again" });
    broken = false;
    await user.click(retry);
    expect(await screen.findByText(/No mandates yet/)).toBeInTheDocument();
  });
});
