/**
 * Wealth (AOS-820, D-79): the shapes of /api/v1/wealth/* and small display helpers. Amounts and percentages arrive as
 * exact decimal strings and are only formatted here, never computed in floating point (D-30).
 */

export const HOLDINGS_PATH = "/api/v1/wealth/holdings";
export const RECONCILIATION_PATH = "/api/v1/wealth/reconciliation";
export const IMPORTS_PATH = "/api/v1/wealth/imports";
export const CAS_UPLOAD_PATH = "/api/v1/wealth/imports/cas";
export const FLOWS_PATH = "/api/v1/wealth/investment-flows";
export const undoPath = (id: string) => `${IMPORTS_PATH}/${encodeURIComponent(id)}/undo`;

export interface WealthReturn { basis: "absolute" | "annualised" | "none"; percent: string | null; days: number; reason: string | null }
export interface HowWeGotThis {
  units: string; price: string | null; priceDate: string | null; priceSource: "amfi" | "statement" | null; stale: boolean;
  staleAfterDays: number | null; lots: number; cashFlows: number; costMethod: string; steps: string[];
}
export interface WealthHolding {
  scheme: string; folio: string; amc: string; plan: "direct" | "regular" | null; isin: string | null; value: string; invested: string; gain: string;
  gainPercent: string | null; realised: string; dividends: string; valuedAtCost: boolean; ret: WealthReturn; issues: string[]; how: HowWeGotThis;
}
export interface HoldingGroup { assetClass: string; value: string; invested: string; holdings: WealthHolding[] }
export interface HoldingStatus {
  folio: string; amc: string; scheme: string; status: "reconciled" | "blocked"; reason: string | null; statementDate: string; statementUnits: string; ledgerUnits: string;
}
export interface WealthOverview {
  asOf: string; pricesAsOf: string | null; stale: number; valuedAtCost: number; value: string; invested: string; gain: string; gainPercent: string | null;
  realised: string; ret: WealthReturn; groups: HoldingGroup[]; needsReview: HoldingStatus[];
}
export interface ImportBatch {
  id: string; reader: string; statementFrom: string | null; statementTo: string | null; status: "applied" | "undone"; added: number; duplicates: number;
  createdAt: string; undoneAt: string | null;
}
export interface CasImportResult { batch: ImportBatch; holdings: HoldingStatus[]; blocked: number }
export interface InvestmentFlows { from: string; to: string; invested: string; redeemed: string; net: string; unmatched: number; flows: unknown[] }

const CLASSES: Record<string, string> = {
  equity_in: "Indian equity", equity_foreign: "Foreign equity", debt: "Debt", hybrid: "Hybrid", gold: "Gold", real_estate: "Real estate",
  cash_like: "Cash-like", crypto: "Crypto", retirement: "Retirement", other: "Other",
};
export const classLabel = (c: string) => CLASSES[c] ?? "Other";

/** "12.3" → "+12.3%", "-4.0" → "−4.0%" (a real minus, a sign in words' place, never colour alone). */
export function signedPercent(p: string): string {
  if (p.startsWith("-")) return `−${p.slice(1)}%`;
  return p === "0.0" ? "0.0%" : `+${p}%`;
}

/** What the return line says: the figure with its basis, or why there is none. */
export function returnText(r: WealthReturn): string {
  if (r.basis === "none" || r.percent === null) return r.reason ?? "Not enough history for a return";
  return r.basis === "absolute" ? `${signedPercent(r.percent)} so far (under a year)` : `${signedPercent(r.percent)} a year (XIRR)`;
}

/** Gain with a word as well as a sign: "Up ₹3,000" / "Down ₹500" / "No change". */
export function gainWord(gain: string): "Up" | "Down" | "No change" {
  if (/^-/.test(gain)) return "Down";
  return /^0(\.0+)?$/.test(gain) ? "No change" : "Up";
}

/** "2026-10-16" → "16 Oct 2026" (dates from the API are plain calendar dates). */
export function dayLabel(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, d)));
}

/** The parser's codes as the owner should read them (anything else: the API's own message). */
export function importError(code: string | undefined, message: string | undefined): string {
  switch (code) {
    case "PASSWORD_REQUIRED": return "This statement is password-protected. Enter the password you chose when you requested it.";
    case "PASSWORD_INCORRECT": return "That password doesn't open this statement. Try again.";
    case "CAS_SUMMARY_UNSUPPORTED": return "This is a Summary statement. Request a Detailed one (with transactions) from CAMS or KFintech.";
    case "CAS_DEPOSITORY_UNSUPPORTED": return "NSDL/CDSL statements aren't supported yet. Upload the CAMS/KFintech statement.";
    case "NOT_A_CAS": case "NOT_A_PDF": return "This doesn't look like a CAMS or KFintech consolidated account statement (PDF).";
    case "FILE_TOO_LARGE": return "The file is over 20 MB.";
    default: return message ?? "The statement couldn't be imported.";
  }
}
