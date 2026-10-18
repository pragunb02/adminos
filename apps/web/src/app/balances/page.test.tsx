import { http } from "msw";
import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import BalancesPage from "./page";

describe("Bank balances page (AOS-720)", () => {
  beforeEach(() => { nav.pathname = "/balances"; });

  it("shows every account's latest balance and how it is chosen", async () => {
    server.use(http.get("/api/v1/balances", () => ok(fx.balances())));
    renderWithQuery(<BalancesPage />);
    expect(screen.getByRole("heading", { level: 2, name: "Bank balances" })).toBeInTheDocument();
    expect(await screen.findByText("HDFC ••1234")).toBeInTheDocument();
    expect(screen.getByText(/Balances older than 7 days are shown but not counted/)).toBeInTheDocument();
  });
});
