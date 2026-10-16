import { describe, expect, it } from "vitest";
import { addMoney, formatInr, fromPaise, toPaise } from "./money";

describe("money (AOS-611, D-30)", () => {
  it("parses decimal strings to paise exactly", () => {
    expect(toPaise("300.30")).toBe(30030n);
    expect(toPaise("1234.5")).toBe(123450n);
    expect(toPaise("7")).toBe(700n);
    expect(toPaise(" -0.05 ")).toBe(-5n);
    expect(toPaise("90071992547409.93")).toBe(9007199254740993n); // beyond float precision, still exact
  });

  it.each(["", "abc", "1.234", "1,000", "₹10", "1e3", "--1", ".5"])("rejects %j", (bad) => {
    expect(() => toPaise(bad)).toThrow(/Not a money amount/);
  });

  it("adds without floating-point error", () => {
    expect(addMoney("100.10", "200.20")).toBe("300.30");
    expect(addMoney("299.67", "649")).toBe("948.67");
    expect(addMoney("0.1", "0.2")).toBe("0.30");
    expect(addMoney()).toBe("0.00");
    expect(addMoney("100", "-100.01")).toBe("-0.01");
    expect(fromPaise(0n)).toBe("0.00");
  });

  it("formats with Indian grouping, paise only when present", () => {
    expect(formatInr("349.00")).toBe("₹349");
    expect(formatInr("12500")).toBe("₹12,500");
    expect(formatInr("100000.00")).toBe("₹1,00,000");
    expect(formatInr("1234567.50")).toBe("₹12,34,567.50");
    expect(formatInr("123456789")).toBe("₹12,34,56,789");
    expect(formatInr("999")).toBe("₹999");
    expect(formatInr("0")).toBe("₹0");
  });

  it("can always show paise, and marks negatives with a real minus sign", () => {
    expect(formatInr("349", { paise: true })).toBe("₹349.00");
    expect(formatInr("-300.00")).toBe("−₹300");
  });
});
