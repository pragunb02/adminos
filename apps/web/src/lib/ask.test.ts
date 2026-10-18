import { describe, expect, it } from "vitest";
import { fx } from "../../test/fixtures";
import { activityHref, askErrorText, periodLabel, resultLines, shortDate, skippedText, stepTitle, type AskStep } from "./ask";

const step = (over: Partial<AskStep> = {}): AskStep => ({ ...fx.askAnswer().steps[0], ...over });
const result = (over: Partial<NonNullable<AskStep["result"]>> = {}) => ({ spent: null, received: null, payments: null, total: null, rows: [], points: [], ...over });

describe("Ask helpers (AOS-711)", () => {
  it("formats dates and periods without a timezone", () => {
    expect(shortDate("2026-10-18")).toBe("18 Oct 2026");
    expect(periodLabel({ from: "2026-09-01", to: "2026-09-30" })).toBe("1 Sep – 30 Sep 2026");
    expect(periodLabel({ from: "2026-10-17", to: "2026-10-17" })).toBe("17 Oct 2026");
    expect(periodLabel({ from: "2025-12-29", to: "2026-01-04" })).toBe("29 Dec 2025 – 4 Jan 2026");
  });

  it("names each tool in plain words", () => {
    expect(stepTitle(step({ tool: "spending_summary" }))).toBe("Total spending");
    expect(stepTitle(step({ tool: "category_spend", category: "food" }))).toBe("Spending on Food");
    expect(stepTitle(step({ tool: "category_spend" }))).toBe("Spending by category");
    expect(stepTitle(step())).toBe("Spending at Zepto");
    expect(stepTitle(step({ merchant: null }))).toBe("Spending at a merchant");
    expect(stepTitle(step({ tool: "top_merchants", limit: 3, category: "emi" }))).toBe("Top 3 merchants in EMI");
    expect(stepTitle(step({ tool: "top_merchants" }))).toBe("Top 5 merchants");
    expect(stepTitle(step({ tool: "spending_trend", bucket: "week", category: "food" }))).toBe("Spending by week on Food");
    expect(stepTitle(step({ tool: "spending_trend" }))).toBe("Spending by day");
  });

  it("links to Activity with the step's dates, category and merchant, safely encoded", () => {
    expect(activityHref(step())).toBe("/transactions?from=2026-10-01&to=2026-10-16&q=Zepto");
    expect(activityHref(step({ tool: "category_spend", merchant: null, category: "food" }))).toBe("/transactions?from=2026-10-01&to=2026-10-16&category=food");
    const hostile = activityHref(step({ merchant: "x&category=transfer#<script>" }))!;
    expect(new URL(hostile, "https://a.example").searchParams.get("category")).toBeNull();
    expect(new URL(hostile, "https://a.example").searchParams.get("q")).toBe("x&category=transfer#<script>");
    expect(activityHref(step({ period: null }))).toBeNull();
  });

  it("lists a step's numbers for the table", () => {
    expect(resultLines(step())).toEqual([{ label: "Zepto", value: "₹1,440 · 4 payments" }, { label: "Total", value: "₹1,440" }]);
    expect(resultLines(step({ tool: "spending_summary", result: result({ spent: "103.00", received: "0.00", payments: 1, total: "103.00" }) }))).toEqual([
      { label: "Spent", value: "₹103" }, { label: "Received", value: "₹0" }, { label: "Payments", value: "1" },
    ]);
    expect(resultLines(step({ tool: "spending_summary", result: result() }))[2]).toEqual({ label: "Payments", value: "0" });
    expect(resultLines(step({ tool: "category_spend", result: result({ total: "50.00", rows: [{ label: "food", spent: "50.00", payments: 1, category: null }] }) }))[0]).toEqual({ label: "Food", value: "₹50 · 1 payment" });
    expect(resultLines(step({ tool: "top_merchants", result: result({ rows: [{ label: "Amazon", spent: "4900.00", payments: 2, category: "shopping" }] }) }))).toEqual([{ label: "Amazon", value: "₹4,900 · 2 payments" }]);
    expect(resultLines(step({ tool: "merchant_spend", result: result({ total: "0.00" }) }))).toEqual([{ label: "No spending", value: "₹0" }, { label: "Total", value: "₹0" }]);
    const week = resultLines(step({ tool: "spending_trend", bucket: "week", result: result({ total: "900.00", points: [{ start: "2026-09-28", spent: "900.00" }] }) }));
    expect(week[0]).toEqual({ label: "Week of 28 Sep 2026", value: "₹900" });
    expect(resultLines(step({ tool: "spending_trend", bucket: "month", result: result({ total: "1.00", points: [{ start: "2026-07-01", spent: "1.00" }] }) }))[0].label).toBe("Jul 2026");
    expect(resultLines(step({ tool: "spending_trend", bucket: "day", result: result({ total: "1.00", points: [{ start: "2026-10-12", spent: "1.00" }] }) }))[0].label).toBe("12 Oct 2026");
    expect(resultLines(step({ result: null }))).toEqual([]);
  });

  it("explains skipped steps and errors calmly", () => {
    expect(skippedText("future_period")).toBe("That period hasn't started yet.");
    expect(skippedText("period_too_long")).toMatch(/3 years/);
    expect(skippedText("too_many_points")).toMatch(/bigger bucket/);
    expect(skippedText("whatever")).toBe("This step couldn't run.");
    expect(askErrorText("ASK_004", "x")).toMatch(/isn't turned on/);
    expect(askErrorText("RATE_001", "Too many questions. Try again in 5 minutes.")).toBe("Too many questions. Try again in 5 minutes.");
    expect(askErrorText("NETWORK", "x")).toMatch(/connection/);
    expect(askErrorText("ASK_006", "This month's AI budget is used up.")).toBe("This month's AI budget is used up.");
    expect(askErrorText("X", "")).toBe("Something went wrong. Try again.");
  });
});
