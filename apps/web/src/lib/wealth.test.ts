import { describe, expect, it } from "vitest";
import { classLabel, dayLabel, gainWord, importError, returnText, signedPercent, undoPath } from "./wealth";

describe("wealth helpers (AOS-820)", () => {
  it("percentages carry a sign, never a float", () => {
    expect(signedPercent("12.3")).toBe("+12.3%");
    expect(signedPercent("-4.0")).toBe("−4.0%");
    expect(signedPercent("0.0")).toBe("0.0%");
  });

  it("the return says its basis or why there is none", () => {
    expect(returnText({ basis: "absolute", percent: "3.0", days: 20, reason: null })).toBe("+3.0% so far (under a year)");
    expect(returnText({ basis: "annualised", percent: "12.5", days: 800, reason: null })).toBe("+12.5% a year (XIRR)");
    expect(returnText({ basis: "none", percent: null, days: 0, reason: "Several rates fit" })).toBe("Several rates fit");
    expect(returnText({ basis: "none", percent: null, days: 0, reason: null })).toBe("Not enough history for a return");
  });

  it("gains have a word as well as a sign", () => {
    expect(gainWord("3000.00")).toBe("Up");
    expect(gainWord("-1.50")).toBe("Down");
    expect(gainWord("0.00")).toBe("No change");
    expect(gainWord("0")).toBe("No change");
  });

  it("labels classes and dates, and encodes ids in paths", () => {
    expect(classLabel("equity_in")).toBe("Indian equity");
    expect(classLabel("mystery")).toBe("Other");
    expect(dayLabel("2026-10-16")).toBe("16 Oct 2026");
    expect(undoPath("a/../b")).toBe("/api/v1/wealth/imports/a%2F..%2Fb/undo");
  });

  it.each([
    ["PASSWORD_REQUIRED", /password-protected/],
    ["PASSWORD_INCORRECT", /doesn't open/],
    ["CAS_SUMMARY_UNSUPPORTED", /Summary statement/],
    ["CAS_DEPOSITORY_UNSUPPORTED", /NSDL/],
    ["NOT_A_CAS", /doesn't look like/],
    ["NOT_A_PDF", /doesn't look like/],
    ["FILE_TOO_LARGE", /20 MB/],
  ])("explains %s", (code, text) => {
    expect(importError(code, "x")).toMatch(text);
  });

  it("falls back to the API message, then a default", () => {
    expect(importError("PARSER_UNAVAILABLE", "The statement parser isn't reachable")).toBe("The statement parser isn't reachable");
    expect(importError(undefined, undefined)).toBe("The statement couldn't be imported.");
  });
});
