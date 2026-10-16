import { describe, expect, it } from "vitest";
import { arrow, browserZone, compactInr, delta, relativeDate } from "./format";

describe("compactInr (axes and tiles)", () => {
  it.each([
    ["0", "₹0"], ["950.75", "₹950"], ["1000", "₹1K"], ["1250", "₹1.3K"], ["99999", "₹100K"], ["100000", "₹1L"],
    ["1234567", "₹12.3L"], ["9999999", "₹100L"], ["10000000", "₹1Cr"], ["123456789", "₹12.3Cr"], ["-48250", "−₹48.3K"],
  ])("%s → %s", (amount, want) => expect(compactInr(amount)).toBe(want));
});

describe("delta (never colour alone)", () => {
  it("says more, less or the same, with the exact amount", () => {
    expect(delta("48250.00", "44050.00")).toEqual({ direction: "up", amount: "4200.00", text: "₹4,200 more" });
    expect(delta("100", "100.50")).toEqual({ direction: "down", amount: "0.50", text: "₹0.50 less" });
    expect(delta("10", "10.00")).toEqual({ direction: "same", amount: "0.00", text: "Same as before" });
    expect([arrow("up"), arrow("down"), arrow("same")]).toEqual(["▲", "▼", "＝"]);
  });
});

describe("relativeDate (in the user's zone)", () => {
  const now = new Date("2026-10-16T06:30:00Z"); // 12:00 in India, a Friday
  it.each([
    ["2026-10-16T01:00:00Z", "Today"],
    ["2026-10-15T20:00:00Z", "Today"], // 01:30 on the 16th in India
    ["2026-10-15T10:00:00Z", "Yesterday"],
    ["2026-10-12T10:00:00Z", "Mon"],
    ["2026-10-09T10:00:00Z", "9 Oct"],
    ["2025-12-31T10:00:00Z", "31 Dec 2025"],
  ])("%s → %s", (iso, want) => expect(relativeDate(iso, "Asia/Kolkata", now)).toBe(want));

  it("the same instant can be a different day elsewhere", () => {
    expect(relativeDate("2026-10-15T20:00:00Z", "America/New_York", now)).toBe("Yesterday");
  });

  it("the browser zone is a real IANA zone", () => {
    expect(browserZone()).toMatch(/\w+/);
  });
});
