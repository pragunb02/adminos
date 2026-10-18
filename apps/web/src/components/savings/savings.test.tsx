import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { cx } from "../../../test/coachFixtures";
import { renderWithQuery } from "../../../test/render";
import { monthName, ratePct, savingsPath, trendText, validPercent } from "@/lib/savings";
import SavingsCard from "./SavingsCard";

describe("savings helpers (AOS-908)", () => {
  it("formats rates and trends in words", () => {
    expect(ratePct("0.3050")).toBe("31%");
    expect(ratePct("-1.0000")).toBe("−100%");
    const [, sep, oct] = cx.savings().months;
    expect(trendText(oct, sep)).toBe("▼ 9 points worse than September");
    expect(trendText(sep, oct)).toBe("▲ 9 points better than October");
    expect(trendText(oct, oct)).toBe("the same as October");
    expect(trendText(oct, null)).toBeNull();
    expect(monthName("2026-10")).toBe("October 2026");
    expect(savingsPath()).toBe("/api/v1/coach/savings?months=12");
    expect(savingsPath({ annualReturnPct: "8.5", inflationPct: "5" })).toBe("/api/v1/coach/savings?months=12&annualReturnPct=8.5&inflationPct=5");
    expect(validPercent("12", 30)).toBe(true);
    for (const bad of ["31", "-1", "1e2", "", "<b>"]) expect(validPercent(bad, 30), bad).toBe(false);
  });
});

describe("Home's savings headline (AOS-908)", () => {
  it("shows the rate, what was saved, the trend and a chart with a table", async () => {
    server.use(http.get("/api/v1/coach/savings", () => ok(cx.savings())));
    const { user } = renderWithQuery(<SavingsCard />);
    const card = await screen.findByRole("region", { name: "Savings rate" });
    expect(await within(card).findByText("31%")).toBeInTheDocument();
    expect(within(card).getByText("Savings rate · October 2026")).toBeInTheDocument();
    expect(within(card).getByText(/Saved ₹30,500 of ₹1,00,000 income · ▼ 9 points worse than September/)).toBeInTheDocument();
    expect(within(card).getByRole("img", { name: /best September 2026 \(40%\); latest 31%/ })).toBeInTheDocument();
    await user.click(within(card).getByRole("button", { name: "View months as table" }));
    const table = within(card).getByRole("table", { name: "Savings rate by month" });
    expect(within(table).getByText("no income")).toBeInTheDocument();
    expect(within(table).getByText("Nov (so far)")).toBeInTheDocument();
    await user.click(within(card).getByRole("button", { name: "View as chart" }));
  });

  it("what last month's leaks could become, with editable assumptions and the formula", async () => {
    const asked: string[] = [];
    server.use(http.get("/api/v1/coach/savings", ({ request }) => { asked.push(new URL(request.url).search); return ok(cx.savings()); }));
    const { user } = renderWithQuery(<SavingsCard />);
    await user.click(await screen.findByText("What October's leaks could become"));
    expect(screen.getByText(/In 5 years:/)).toHaveTextContent("In 5 years: ₹4,57,799 (₹3,42,094 in today's money at 6% inflation; you'd have put in ₹3,33,000)");
    await user.click(screen.getByText("How we got this"));
    expect(screen.getByText(/FV = P × \(\(1 \+ r\)\^n − 1\)/)).toBeInTheDocument();
    const ret = screen.getByLabelText("Expected return, percent a year");
    await user.clear(ret);
    await user.type(ret, "40");
    expect(screen.getByRole("button", { name: "Recalculate" })).toBeDisabled();
    await user.clear(ret);
    await user.type(ret, "8.5");
    await user.click(screen.getByRole("button", { name: "Recalculate" }));
    await waitFor(() => expect(asked).toContain("?months=12&annualReturnPct=8.5&inflationPct=6"));
    await user.click(screen.getByRole("button", { name: "Defaults (12% / 6%)" }));
    await waitFor(() => expect(asked).toContain("?months=12&annualReturnPct=12&inflationPct=6"));
  });

  it("too little data says what's needed; a single month shows no chart; errors retry", async () => {
    const { unmount } = renderWithQuery(<SavingsCard />);
    expect(await screen.findByText(/Not enough data yet/)).toBeInTheDocument();
    unmount();

    const one = cx.savings({ months: [cx.savings().months[2]], previous: null, projection: null });
    server.use(http.get("/api/v1/coach/savings", () => ok(one)));
    const second = renderWithQuery(<SavingsCard />);
    expect(await screen.findByText("31%")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(screen.queryByText(/could become/)).not.toBeInTheDocument();
    second.unmount();

    let n = 0;
    server.use(http.get("/api/v1/coach/savings", () => (n++ === 0 ? fail(500, "SERVER_001", "Down") : ok(cx.savings({ headline: { ...cx.savings().months[3], rate: "0.1000", partial: true } })))));
    const third = renderWithQuery(<SavingsCard />);
    await third.user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Savings rate · November 2026 so far")).toBeInTheDocument();
  });
});
