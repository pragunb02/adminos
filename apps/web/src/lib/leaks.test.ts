import { describe, expect, it } from "vitest";
import { activityHref, changeText, leakTransactionsPath, leaksPath, lensOverridePath, lensResetPath, percent, readLeakRange, ruleSummary } from "./leaks";

const TODAY = "2026-10-18";
const q = (s: string) => new URLSearchParams(s);

describe("leaks helpers (AOS-902)", () => {
  it("builds the API paths with encoded values", () => {
    expect(leaksPath({ from: "2026-10-12", to: "2026-10-18" })).toBe("/api/v1/coach/leaks?from=2026-10-12&to=2026-10-18");
    expect(lensOverridePath("nights_out")).toBe("/api/v1/coach/lenses/nights_out/merchants");
    expect(lensOverridePath("../admin")).toBe("/api/v1/coach/lenses/..%2Fadmin/merchants");
    expect(lensResetPath("shopping", "H&M <b>")).toBe("/api/v1/coach/lenses/shopping/merchants?merchant=H%26M+%3Cb%3E");
  });

  it("links every number to Activity with its slice", () => {
    const r = { from: "2026-10-12", to: "2026-10-18" };
    expect(activityHref(r)).toBe("/transactions?lens=all&from=2026-10-12&to=2026-10-18");
    expect(activityHref(r, { lens: "quick_commerce", merchant: "Zepto & Co", late: true, weekday: "tue" }))
      .toBe("/transactions?lens=quick_commerce&from=2026-10-12&to=2026-10-18&merchant=Zepto+%26+Co&late=1&weekday=tue");
  });

  it("turns Activity's lens filters into the drill-down path, passing only spend or refund", () => {
    const base = { lens: "quick_commerce", merchant: "", late: false, weekday: "", from: "", to: "", category: "", nature: "", q: "" };
    expect(leakTransactionsPath(base)).toBe("/api/v1/coach/leaks/transactions?lens=quick_commerce&limit=1000");
    expect(leakTransactionsPath({ ...base, merchant: "Zepto", late: true, weekday: "mon", from: "2026-10-01", to: "2026-10-18", category: "shopping", nature: "refund", q: "zep" }))
      .toBe("/api/v1/coach/leaks/transactions?lens=quick_commerce&limit=1000&from=2026-10-01&to=2026-10-18&merchant=Zepto&lateNight=true&weekday=mon&category=shopping&nature=refund&q=zep");
    expect(leakTransactionsPath({ ...base, nature: "income", from: "2026-10-01" })).toBe("/api/v1/coach/leaks/transactions?lens=quick_commerce&limit=1000");
  });

  it("reads a custom range, or the period, from the URL", () => {
    expect(readLeakRange(q("from=2026-09-01&to=2026-09-30"), TODAY)).toEqual({ range: { from: "2026-09-01", to: "2026-09-30" }, custom: true, notice: null });
    expect(readLeakRange(q(""), TODAY)).toEqual({ range: { from: "2026-10-01", to: "2026-10-18" }, custom: false, notice: null });
    expect(readLeakRange(q("p=week&d=2026-10-14"), TODAY).range).toEqual({ from: "2026-10-12", to: "2026-10-18" });
  });

  it("falls back to the period with a notice for a bad custom range", () => {
    const cases: [string, RegExp][] = [
      ["from=2026-09-01", /both a start and an end/],
      ["from=2026-13-01&to=2026-12-01", /dates look like/],
      ["from=2026-02-30&to=2026-03-01", /dates look like/],
      ["from=2026-10-10&to=2026-10-01", /start is after the end/],
      ["from=2026-10-10&to=2026-10-30", /in the future/],
      ["from=2020-01-01&to=2026-10-01", /at most 1098 days/],
      ["from=<script>&to=x", /dates look like/],
    ];
    for (const [s, why] of cases) {
      const r = readLeakRange(q(s), TODAY);
      expect(r.custom, s).toBe(false);
      expect(r.range, s).toEqual({ from: "2026-10-01", to: "2026-10-18" });
      expect(r.notice, s).toMatch(why);
    }
  });

  it("formats shares, changes and the rules", () => {
    expect(percent("0.6667")).toBe("67%");
    expect(percent("0.0000")).toBe("0%");
    expect(changeText(null)).toBeNull();
    expect(changeText("140.0")).toBe("▲ 140% more than before");
    expect(changeText("-25.6")).toBe("▼ 26% less than before");
    expect(changeText("0.3")).toBe("about the same as before");
    expect(ruleSummary({ brands: ["Zepto", "Blinkit"], categories: [], subcategories: [], keywords: [] })).toBe("2 apps and shops");
    expect(ruleSummary({ brands: ["Toit"], categories: ["entertainment"], subcategories: ["food_delivery"], keywords: ["bar", "pub", "club", "lounge"] }))
      .toBe("1 app or shop; anything categorised entertainment, food delivery; names with bar, pub, club…");
    expect(ruleSummary({ brands: [], categories: [], subcategories: [], keywords: ["bar"] })).toBe("names with bar");
    expect(ruleSummary({ brands: [], categories: [], subcategories: [], keywords: [] })).toBe("only the merchants you add");
  });

  // AOS-913: the payment-method rule from AOS-912.
  it("describes a payment-method rule instead of only the merchants you add", () => {
    expect(ruleSummary({ brands: [], categories: [], subcategories: [], keywords: [], paymentMethods: ["pay_later"] })).toBe("purchases paid with pay later");
  });

  it("names several methods, and puts the method first beside other rules", () => {
    expect(ruleSummary({ brands: [], categories: [], subcategories: [], keywords: [], paymentMethods: ["upi", "card_credit", "card_debit"] }))
      .toBe("purchases paid with UPI or credit card or debit card");
    expect(ruleSummary({ brands: ["Zepto"], categories: [], subcategories: ["groceries"], keywords: [], paymentMethods: ["pay_later"] }))
      .toBe("purchases paid with pay later; 1 app or shop; anything categorised groceries");
  });

  it("an unknown method reads as words, and a missing or empty list is no rule", () => {
    expect(ruleSummary({ brands: [], categories: [], subcategories: [], keywords: [], paymentMethods: ["crypto_voucher"] })).toBe("purchases paid with crypto voucher");
    expect(ruleSummary({ brands: [], categories: [], subcategories: [], keywords: [], paymentMethods: [] })).toBe("only the merchants you add");
    expect(ruleSummary({ brands: ["IndiGo"], categories: [], subcategories: [], keywords: [] })).toBe("1 app or shop");
  });

  it("does not read inherited object keys as method names", () => {
    expect(ruleSummary({ brands: [], categories: [], subcategories: [], keywords: [], paymentMethods: ["constructor", "__proto__"] }))
      .toBe("purchases paid with constructor or proto");
  });
});
