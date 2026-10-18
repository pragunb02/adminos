/**
 * Latest balance per bank account (AOS-720, D-170). Money is an exact decimal string (D-30); every figure is the
 * API's, read from a trusted bank SMS or a statement's closing balance. The page only formats and labels it.
 */
export type Freshness = "fresh" | "aging" | "stale";
export type BalanceSource = "sms" | "statement";

export interface AccountBalance {
  bank: string | null;
  last4: string;
  amount: string;
  observedAt: string;
  observedOn: string;
  source: BalanceSource;
  ageDays: number;
  freshness: Freshness;
  counted: boolean;
}

export interface BalancesData {
  today: string;
  accounts: AccountBalance[];
  total: string;
  countedAccounts: number;
  maxAgeDays: number;
}

export const BALANCES_PATH = "/api/v1/balances";

/** Bank codes as people say them; an unknown code is shown as given, upper-cased. */
const BANK_NAME: Record<string, string> = { hdfc: "HDFC", sbi: "SBI", icici: "ICICI", axis: "Axis", kotak: "Kotak" };

/** "HDFC ••1234", or "Account ••1234" when no input named the bank. */
export function accountLabel(bank: string | null, last4: string): string {
  const name = bank ? (BANK_NAME[bank] ?? bank.toUpperCase()) : "Account";
  return `${name} ••${last4}`;
}

export const SOURCE_LABEL: Record<BalanceSource, string> = { sms: "bank SMS", statement: "statement" };

/** How old the figure is, in words: colour is never the only signal (D-49). */
export function ageText(b: Pick<AccountBalance, "ageDays" | "freshness">, maxAgeDays: number): string {
  const when = b.ageDays === 0 ? "today" : b.ageDays === 1 ? "yesterday" : `${b.ageDays} days ago`;
  return b.freshness === "stale" ? `${when} · too old to count (over ${maxAgeDays} days)` : when;
}
