import { describe, expect, it } from "vitest";
import { accountLabel, ageText } from "./balances";

describe("balances helpers (AOS-720)", () => {
  it("names accounts by bank and last digits", () => {
    expect(accountLabel("hdfc", "1234")).toBe("HDFC ••1234");
    expect(accountLabel("yes_bank", "0042")).toBe("YES_BANK ••0042");
    expect(accountLabel(null, "9012")).toBe("Account ••9012");
  });

  it("says a balance's age in words, and when it's too old to count", () => {
    expect(ageText({ ageDays: 0, freshness: "fresh" }, 7)).toBe("today");
    expect(ageText({ ageDays: 1, freshness: "fresh" }, 7)).toBe("yesterday");
    expect(ageText({ ageDays: 5, freshness: "aging" }, 7)).toBe("5 days ago");
    expect(ageText({ ageDays: 16, freshness: "stale" }, 7)).toBe("16 days ago · too old to count (over 7 days)");
  });
});
