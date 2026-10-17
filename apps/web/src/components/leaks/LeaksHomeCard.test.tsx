import { http } from "msw";
import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { cx } from "../../../test/coachFixtures";
import { nav } from "../../../test/next";
import { renderWithQuery } from "../../../test/render";
import LeaksHomeCard from "./LeaksHomeCard";

const range = { from: "2026-10-12", to: "2026-10-18" };

describe("Home's Where it leaks card (AOS-902)", () => {
  it("shows the total and the biggest lens, linking with the period", async () => {
    nav.search = new URLSearchParams("p=week&d=2026-10-14&view=x");
    server.use(http.get("/api/v1/coach/leaks", () => ok(cx.leaks())));
    renderWithQuery(<LeaksHomeCard range={range} />);
    expect(await screen.findByText("₹951")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Quick commerce ₹1,200" })).toHaveAttribute("href", "/leaks?p=week&d=2026-10-14");
    expect(screen.getByText(/2 late at night/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All ›" })).toHaveAttribute("href", "/leaks?p=week&d=2026-10-14");
  });

  it("says when nothing leaked", async () => {
    renderWithQuery(<LeaksHomeCard range={range} />);
    expect(await screen.findByText("No habit spending in this period.")).toBeInTheDocument();
  });

  it("a single order without late nights reads correctly", async () => {
    const data = cx.leaks();
    data.total = { ...data.total, orders: 1 };
    data.lenses = [{ ...data.lenses[1] }];
    server.use(http.get("/api/v1/coach/leaks", () => ok(data)));
    renderWithQuery(<LeaksHomeCard range={range} />);
    expect(await screen.findByText(/over 1 order\./)).toBeInTheDocument();
    expect(screen.queryByText(/late at night/)).not.toBeInTheDocument();
  });

  it("an error has a retry", async () => {
    let n = 0;
    server.use(http.get("/api/v1/coach/leaks", () => (n++ === 0 ? fail(500, "SERVER_001", "Nope") : ok(cx.leaks()))));
    const { user } = renderWithQuery(<LeaksHomeCard range={range} />);
    await user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText("₹951")).toBeInTheDocument();
  });
});
