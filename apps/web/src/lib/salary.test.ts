import { describe, expect, it } from "vitest";
import { checkCorrection, ordinal, salarySourceBadge } from "./salary";

const valid = { payer: "Example Employer", day: "30", amount: "85000" };

describe("salary helpers (AOS-722)", () => {
  it("accepts what the API accepts, trimmed, with commas dropped from the amount", () => {
    expect(checkCorrection({ payer: "  Example Employer ", day: " 1 ", amount: "1,00,000.50" })).toEqual({ ok: true, value: { payer: "Example Employer", day: 1, amount: "100000.50" } });
    expect(checkCorrection({ ...valid, payer: "   " })).toEqual({ ok: true, value: { payer: null, day: 30, amount: "85000" } });
    expect(checkCorrection({ ...valid, payer: "x".repeat(100), day: "31", amount: "1000000000.00" }).ok).toBe(true);
  });

  it.each(["0", "32", "-1", "1.5", "", "abc", "1e1", "100"])("refuses the day %j", (day) => {
    expect(checkCorrection({ ...valid, day })).toEqual({ ok: false, errors: { day: "Day of month: a whole number from 1 to 31." } });
  });

  it.each(["", "abc", "-5", "1e5", "1.234", "85 000", "12345678901", "<script>"])("refuses the amount %j", (amount) => {
    expect(checkCorrection({ ...valid, amount })).toEqual({ ok: false, errors: { amount: "Amount: a rupee amount like 85000 or 85000.50." } });
  });

  it("refuses zero and more than ₹100 crore", () => {
    expect(checkCorrection({ ...valid, amount: "0.00" })).toEqual({ ok: false, errors: { amount: "Amount: more than zero." } });
    expect(checkCorrection({ ...valid, amount: "1000000000.01" })).toEqual({ ok: false, errors: { amount: "Amount: at most ₹1,00,00,00,000." } });
  });

  // bidi override, zero-width space, NUL, newline, BOM, line separator, zero-width joiner, a lone surrogate
  it.each(["Acme\u202ECorp", "Acme\u200BCorp", "Acme\u0000", "Ac\nme", "Acme\uFEFFCorp", "Ac\u2028me", "Ac\u200Dme", "Acme\uD800"])("refuses hidden characters in the payer %#", (payer) => {
    expect(checkCorrection({ ...valid, payer })).toEqual({ ok: false, errors: { payer: "Payer: letters, digits and punctuation only." } });
  });

  it("refuses a payer over 100 characters and reports every problem at once", () => {
    expect(checkCorrection({ payer: "x".repeat(101), day: "0", amount: "" })).toEqual({
      ok: false,
      errors: { payer: "Payer: at most 100 characters.", day: "Day of month: a whole number from 1 to 31.", amount: "Amount: a rupee amount like 85000 or 85000.50." },
    });
  });

  it("keeps a hostile-looking but printable payer for the API to judge; it is shown as text", () => {
    expect(checkCorrection({ ...valid, payer: "<img src=x onerror=alert(1)>" }).ok).toBe(true);
  });

  it("ordinals and the confirmed badge", () => {
    expect([1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 30, 31].map(ordinal)).toEqual(["1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "30th", "31st"]);
    expect(salarySourceBadge("confirmed")).toBe("confirmed");
    expect(salarySourceBadge("detected")).toBeNull();
    expect(salarySourceBadge(undefined)).toBeNull();
  });
});
