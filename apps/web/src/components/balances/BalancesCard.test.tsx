import { http } from "msw";
import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import BalancesCard from "./BalancesCard";

const balancesWith = (data: ReturnType<typeof fx.balances>) => server.use(http.get("/api/v1/balances", () => ok(data)));

/** Home → Bank balances (AOS-720). */
describe("BalancesCard", () => {
  it("shows the counted total and each account with its age and source", async () => {
    balancesWith(fx.balances());
    renderWithQuery(<BalancesCard />);
    const card = await screen.findByRole("region", { name: "Bank balances" });
    expect(await within(card).findByText("₹41,300.50")).toBeInTheDocument();
    expect(within(card).getByText("In 2 accounts from the last 7 days")).toBeInTheDocument();
    const rows = within(within(card).getByRole("list", { name: "Accounts" })).getAllByRole("listitem");
    expect(rows.map((r) => r.textContent)).toEqual([
      "HDFC ••1234today · from a bank SMS₹42,500.50",
      "SBI ••56784 days ago · from a statementOverdrawn ₹1,200",
      "Account ••901216 days ago · too old to count (over 7 days) · from a bank SMS₹8,000",
    ]);
    expect(within(card).getByRole("link", { name: "All ›" })).toHaveAttribute("href", "/balances");
    expect(within(card).queryByText(/never by when it arrived/)).not.toBeInTheDocument();
  });

  it("says when nothing is known, and how a balance gets in", async () => {
    renderWithQuery(<BalancesCard />);
    expect(await screen.findByText(/No balance known yet. A bank SMS that shows "Avl Bal", or an imported statement, adds one./)).toBeInTheDocument();
  });

  it("with only stale balances, gives no total", async () => {
    const f = fx.balances();
    balancesWith(fx.balances({ accounts: [f.accounts[2]], total: "0.00", countedAccounts: 0 }));
    renderWithQuery(<BalancesCard />);
    expect(await screen.findByText("None from the last 7 days")).toBeInTheDocument();
    expect(screen.queryByText("₹0")).not.toBeInTheDocument();
  });

  it("one counted account reads in the singular", async () => {
    const f = fx.balances();
    balancesWith(fx.balances({ accounts: [f.accounts[0]], total: "42500.50", countedAccounts: 1 }));
    renderWithQuery(<BalancesCard />);
    expect(await screen.findByText("In 1 account from the last 7 days")).toBeInTheDocument();
  });

  it("the full view explains how the latest is chosen", async () => {
    balancesWith(fx.balances());
    renderWithQuery(<BalancesCard full />);
    expect(await screen.findByText(/never by when it arrived/)).toBeInTheDocument();
    expect(screen.getByText(/Credit cards, wallets and loans are never balances/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "All ›" })).not.toBeInTheDocument();
  });

  it("an error shows with a retry that loads it", async () => {
    let n = 0;
    server.use(http.get("/api/v1/balances", () => (++n === 1 ? fail(400, "VALIDATION_003", "Balances are down") : ok(fx.balances()))));
    const { user } = renderWithQuery(<BalancesCard />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Balances are down");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("₹41,300.50")).toBeInTheDocument();
  });

  it("renders a hostile bank code as text", async () => {
    const f = fx.balances();
    balancesWith(fx.balances({ accounts: [{ ...f.accounts[0], bank: '<img src=x onerror="alert(1)">' }] }));
    const { container } = renderWithQuery(<BalancesCard />);
    expect(await screen.findByText(/<IMG SRC=X ONERROR="ALERT\(1\)"> ••1234/)).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });
});
