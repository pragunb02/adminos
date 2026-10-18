/**
 * Cash quick-add types and words (AOS-204, AOS-718). Amounts stay exact decimal strings (D-30).
 * Every entry read from text is a preview: nothing is saved until the user confirms it.
 */

/** Categories a cash spend can have (never Transfer: an ATM withdrawal already is one, D-42). */
export const CASH_CATEGORIES = [
  { value: "food", label: "Food", icon: "🍽️" },
  { value: "transport", label: "Transport", icon: "🛺" },
  { value: "shopping", label: "Shopping", icon: "🛍️" },
  { value: "health", label: "Health", icon: "💊" },
  { value: "entertainment", label: "Fun", icon: "🎬" },
  { value: "utilities", label: "Bills", icon: "💡" },
  { value: "education", label: "Education", icon: "📚" },
  { value: "other", label: "Other", icon: "•••" },
] as const;

/** Extra categories a parsed entry may come back with; selectable in the preview. */
export const MORE_CATEGORIES = [
  { value: "emi", label: "EMI" },
  { value: "subscription", label: "Subscription" },
] as const;

/** A plain amount: up to 8 digits and 2 decimals. */
export const AMOUNT = /^\d{1,8}(\.\d{1,2})?$/;
export const validAmount = (a: string) => AMOUNT.test(a) && Number(a) > 0;

export type DraftWarning = "category_unknown" | "date_assumed" | "quantity_assumed" | "old_date" | "no_note";
export type UnparsedReason = "no_amount" | "zero_amount" | "negative_amount" | "too_large" | "too_precise" | "future_date" | "invalid_date";

export interface ParsedEntry {
  amount: string;
  category: string;
  note: string | null;
  date: string;
  evidence: string;
  categoryFrom: string;
  warnings: DraftWarning[];
}

export interface CashParse {
  today: string;
  entries: ParsedEntry[];
  unparsed: { text: string; reason: UnparsedReason }[];
}

export const PARSE_PATH = "/api/v1/transactions/cash/parse";
export const CASH_PATH = "/api/v1/transactions/cash";
export const MAX_TEXT = 500;

export const WARNING_TEXT: Record<DraftWarning, string> = {
  category_unknown: "Pick a category: it wasn't clear.",
  date_assumed: "“kal” or “parso” was read as the past.",
  quantity_assumed: "A number was read as a count, not the amount: check it.",
  old_date: "That's over a year ago.",
  no_note: "No note.",
};

export const UNPARSED_TEXT: Record<UnparsedReason, string> = {
  no_amount: "no amount",
  zero_amount: "the amount is 0",
  negative_amount: "the amount is negative",
  too_large: "over ₹1 crore",
  too_precise: "more than 2 decimal places",
  future_date: "a date after today (cash is added once it's spent)",
  invalid_date: "no such date",
};
