import type { CasImportResult, HoldingStatus, ImportBatch, InvestmentFlows, WealthHolding, WealthOverview } from "@/lib/wealth";

/** SYNTHETIC wealth data (AOS-820): invented schemes, folios (last 4 only) and amounts, matching the API's synthetic CAS. */
export const wx = {
  holding: (over: Partial<WealthHolding> = {}): WealthHolding => ({
    scheme: "Example Flexi Cap Fund - Direct Plan - Growth", folio: "••••4/56", amc: "Example Mutual Fund", plan: "direct", isin: "INFZZ9000010",
    value: "18000.00", invested: "15000.00", gain: "3000.00", gainPercent: "20.0", realised: "500.00", dividends: "0.00", valuedAtCost: false,
    ret: { basis: "absolute", percent: "17.5", days: 357, reason: null }, issues: [],
    how: {
      units: "150", price: "120", priceDate: "2025-12-31", priceSource: "statement", stale: false, staleAfterDays: 4, lots: 3, cashFlows: 7, costMethod: "fifo",
      steps: ["Units held: 150 (from 7 transactions in your imported statements)", "Value = units × price = 18000.00", "Return: held under a year (357 days), so shown as an absolute return, not a yearly rate"],
    },
    ...over,
  }),
  blocked: (over: Partial<HoldingStatus> = {}): HoldingStatus => ({
    folio: "••••5678", amc: "Sample Mutual Fund", scheme: "Example Liquid Fund - Regular Plan - Growth", status: "blocked",
    reason: "Units don't add up: the statement says 11 units, the transactions give 10", statementDate: "2025-12-31", statementUnits: "11", ledgerUnits: "10", ...over,
  }),
  overview: (over: Partial<WealthOverview> = {}): WealthOverview => ({
    asOf: "2026-01-02", pricesAsOf: "2025-12-31", stale: 0, valuedAtCost: 0, value: "18000.00", invested: "15000.00", gain: "3000.00", gainPercent: "20.0",
    realised: "500.00", ret: { basis: "absolute", percent: "17.5", days: 357, reason: null },
    groups: [{ assetClass: "equity_in", value: "18000.00", invested: "15000.00", holdings: [wx.holding()] }], needsReview: [], ...over,
  }),
  empty: (): WealthOverview => wx.overview({ value: "0.00", invested: "0.00", gain: "0.00", gainPercent: null, realised: "0.00", pricesAsOf: null,
    ret: { basis: "none", percent: null, days: 0, reason: "Not enough transactions to compute a return" }, groups: [], needsReview: [] }),
  batch: (over: Partial<ImportBatch> = {}): ImportBatch => ({
    id: "b-1", reader: "casparser", statementFrom: "2025-01-01", statementTo: "2025-12-31", status: "applied", added: 8, duplicates: 0,
    createdAt: "2026-01-02T06:00:00Z", undoneAt: null, ...over,
  }),
  importResult: (over: Partial<CasImportResult> = {}): CasImportResult => ({
    batch: wx.batch(), holdings: [{ ...wx.blocked(), status: "reconciled", reason: null, statementUnits: "150", ledgerUnits: "150" }, wx.blocked()], blocked: 1, ...over,
  }),
  flows: (over: Partial<InvestmentFlows> = {}): InvestmentFlows => ({
    from: "2025-10-05", to: "2026-01-02", invested: "10000.00", redeemed: "5500.00", net: "4500.00", unmatched: 1, flows: [], ...over,
  }),
};
