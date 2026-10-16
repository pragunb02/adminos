import { describe, expect, it } from "vitest";
import { cumulativeByDay, mergeForChart } from "./cumulative";

const pt = (d: string, spent: string) => ({ start: d, spent, received: "0.00" });

describe("running total (exact, D-46)", () => {
  it("adds day by day in paise; a refund-only day lowers it", () => {
    expect(cumulativeByDay([pt("2026-10-01", "100.10"), pt("2026-10-02", "200.20"), pt("2026-10-03", "-50.00")]))
      .toEqual([{ day: 1, total: "100.10" }, { day: 2, total: "300.30" }, { day: 3, total: "250.30" }]);
    expect(cumulativeByDay([])).toEqual([]);
  });

  it("merges this month so far with the whole of last month", () => {
    const merged = mergeForChart([{ day: 1, total: "10.00" }, { day: 2, total: "30.50" }], [{ day: 1, total: "5.00" }, { day: 2, total: "6.00" }, { day: 3, total: "7.00" }]);
    expect(merged).toEqual([
      { day: 1, current: 10, previous: 5, currentExact: "10.00", previousExact: "5.00" },
      { day: 2, current: 30.5, previous: 6, currentExact: "30.50", previousExact: "6.00" },
      { day: 3, current: null, previous: 7, currentExact: null, previousExact: "7.00" },
    ]);
    expect(mergeForChart([], [])).toEqual([]);
    expect(mergeForChart([{ day: 1, total: "1.00" }], [])).toHaveLength(1);
  });
});
