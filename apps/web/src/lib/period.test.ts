import { describe, expect, it } from "vitest";
import { comparisonRange, describe as label, isCurrent, parsePeriod, periodQuery, rangeOf, shift, todayIn, type Period } from "./period";

const today = "2026-10-16"; // a Friday
const P = (kind: Period["kind"], anchor: string): Period => ({ kind, anchor });
const q = (s: string) => new URLSearchParams(s);

describe("period in the URL", () => {
  it("defaults to this month with no notice", () => {
    expect(parsePeriod(q(""), today)).toEqual({ period: P("month", today), notice: null });
  });

  it("reads p and d", () => {
    expect(parsePeriod(q("p=week&d=2026-10-05"), today).period).toEqual(P("week", "2026-10-05"));
    expect(parsePeriod(q("p=today"), today).period).toEqual(P("today", today));
  });

  it.each([
    ["p=year", "Showing October 2026: 'year' isn't a period."],
    ["d=2026-13-01", "Showing October 2026: '2026-13-01' isn't a date."],
    ["d=2026-02-30", "Showing October 2026: '2026-02-30' isn't a date."],
    ["d=2026-10-20", "Showing October 2026: 2026-10-20 is in the future."],
    ["p=x&d=y", "Showing October 2026: 'x' isn't a period, 'y' isn't a date."],
  ])("invalid %s falls back and says why", (s, notice) => {
    const r = parsePeriod(q(s), today);
    expect(r.period).toEqual(P("month", today));
    expect(r.notice).toBe(notice);
  });

  it("serialises; the default month is the empty query", () => {
    expect(periodQuery(P("month", "2026-10-02"), today)).toBe("");
    expect(periodQuery(P("month", "2026-09-01"), today)).toBe("?p=month&d=2026-09-01");
    expect(periodQuery(P("week", "2026-10-12"), today)).toBe("?p=week&d=2026-10-12");
    expect(periodQuery(P("today", today), today)).toBe("?p=today&d=2026-10-16");
  });
});

describe("ranges", () => {
  it("months run from the 1st, capped at today", () => {
    expect(rangeOf(P("month", "2026-10-09"), today)).toEqual({ from: "2026-10-01", to: "2026-10-16" });
    expect(rangeOf(P("month", "2026-02-11"), today)).toEqual({ from: "2026-02-01", to: "2026-02-28" });
    expect(rangeOf(P("month", "2024-02-11"), today)).toEqual({ from: "2024-02-01", to: "2024-02-29" });
  });
  it("weeks run Monday to Sunday", () => {
    expect(rangeOf(P("week", "2026-10-16"), today)).toEqual({ from: "2026-10-12", to: "2026-10-16" });
    expect(rangeOf(P("week", "2026-10-11"), today)).toEqual({ from: "2026-10-05", to: "2026-10-11" }); // a Sunday
  });
  it("a day is itself", () => {
    expect(rangeOf(P("today", "2026-10-14"), today)).toEqual({ from: "2026-10-14", to: "2026-10-14" });
  });
});

describe("moving between periods", () => {
  it("back and forward by month, week and day; never past the current one", () => {
    expect(shift(P("month", today), -1, today)).toEqual(P("month", "2026-09-01"));
    expect(shift(P("month", "2026-09-01"), 1, today)).toEqual(P("month", "2026-10-01"));
    expect(shift(P("month", "2026-01-15"), -1, today)).toEqual(P("month", "2025-12-01"));
    expect(shift(P("month", today), 1, today)).toEqual(P("month", today));
    expect(shift(P("week", today), -1, today)).toEqual(P("week", "2026-10-05"));
    expect(shift(P("week", "2026-10-05"), 1, today)).toEqual(P("week", "2026-10-12"));
    expect(shift(P("today", today), -1, today)).toEqual(P("today", "2026-10-15"));
    expect(shift(P("today", today), 1, today)).toEqual(P("today", today));
  });
  it("knows the current period", () => {
    expect(isCurrent(P("month", "2026-10-01"), today)).toBe(true);
    expect(isCurrent(P("week", "2026-10-12"), today)).toBe(true);
    expect(isCurrent(P("week", "2026-10-11"), today)).toBe(false);
  });
});

describe("labels", () => {
  it.each([
    [P("month", "2026-10-03"), "October 2026"],
    [P("week", today), "This week"],
    [P("week", "2026-10-05"), "5–11 Oct"],
    [P("week", "2026-09-28"), "28 Sept – 4 Oct"],
    [P("today", today), "Today"],
    [P("today", "2026-10-15"), "Yesterday"],
    [P("today", "2026-10-02"), "2 Oct"],
  ])("%o → %s", (p, want) => expect(label(p, today)).toBe(want));

  it("today in a zone", () => {
    const now = new Date("2026-10-15T20:00:00Z");
    expect(todayIn("Asia/Kolkata", now)).toBe("2026-10-16");
    expect(todayIn("America/New_York", now)).toBe("2026-10-15");
  });
});

describe("like-for-like comparison (D-49)", () => {
  it("month so far vs the same days of last month", () => {
    expect(comparisonRangeOf(P("month", today))).toEqual({ from: "2026-09-01", to: "2026-09-16", label: "by 16 Sept" });
  });
  it("a whole past month vs the whole month before, capped at its last day", () => {
    expect(comparisonRangeOf(P("month", "2026-03-31"))).toEqual({ from: "2026-02-01", to: "2026-02-28", label: "by 28 Feb" });
  });
  it("week so far vs last week up to the same weekday", () => {
    expect(comparisonRangeOf(P("week", today))).toEqual({ from: "2026-10-05", to: "2026-10-09", label: "by 9 Oct" });
  });
  it("a day vs the same weekday a week earlier", () => {
    expect(comparisonRangeOf(P("today", today))).toEqual({ from: "2026-10-09", to: "2026-10-09", label: "last Friday" });
  });
});
const comparisonRangeOf = (p: Period) => comparisonRange(p, today);
