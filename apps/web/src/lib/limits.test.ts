import { describe, expect, it } from "vitest";
import { cx } from "../../test/coachFixtures";
import { dismissSuggestionPath, limitPath, limitText, usedPercent, validAmount } from "./limits";

describe("limits helpers (AOS-904)", () => {
  it("builds encoded paths", () => {
    expect(limitPath("a/b")).toBe("/api/v1/coach/limits/a%2Fb");
    expect(dismissSuggestionPath("../x")).toBe("/api/v1/coach/limits/suggestions/..%2Fx/dismiss");
  });

  it("describes progress in words", () => {
    expect(usedPercent("0.6000")).toBe(60);
    expect(limitText(cx.limit())).toBe("60% used · ₹800 left · last day");
    expect(limitText(cx.limit({ status: { ...cx.limit().status, used: "1.1000", left: "-200.00", daysLeft: 1 } }))).toBe("110% used · ₹200 over · 1 day to go");
    expect(limitText(cx.limit({ status: { ...cx.limit().status, daysLeft: 4 } }))).toBe("60% used · ₹800 left · 4 days to go");
  });

  it("accepts only amounts the API accepts", () => {
    for (const ok of ["1", "2000", "1999.5", "1999.50", " 300 "]) expect(validAmount(ok), ok).toBe(true);
    for (const bad of ["", "0", "0.00", "-5", "1e9", "1.234", "123456789", "abc", "<script>"]) expect(validAmount(bad), bad).toBe(false);
  });
});
