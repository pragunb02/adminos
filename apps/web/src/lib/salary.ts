/**
 * Confirm or correct the detected salary (AOS-722). Money is an exact decimal string (D-30). The checks here
 * mirror the API's (SalaryChoice, SalaryRoutes) so a bad value is caught before it is sent; the API still decides.
 */
import { toPaise } from "./money";
import { getData, putData } from "./query";

export interface DetectedSalary {
  payer: string | null;
  usualDay: number;
  typicalAmount: string;
  lastAmount: string;
  lastReceivedOn: string;
  expectedOn: string;
  credits: number;
}

export type SalaryChoiceKind = "confirmed" | "corrected";

export interface SalaryChoice { payer: string | null; day: number; amount: string; kind: SalaryChoiceKind; updatedAt: string }

/** `GET` and `PUT /api/v1/forecast/salary`: what detection alone finds, why not, and the owner's stored choice. */
export interface SalaryView { detected: DetectedSalary | null; detectedReason: string | null; choice: SalaryChoice | null }

/** A correction ready to send: payer trimmed (null = credits that come without a name), amount a plain decimal. */
export interface SalaryCorrection { payer: string | null; day: number; amount: string }

export const SALARY_PATH = "/api/v1/forecast/salary";
export const SALARY_QUERY_KEY = ["forecast-salary"] as const;
export const MAX_PAYER = 100;
const MAX_AMOUNT_PAISE = 100_000_000_000n; // ₹1,00,00,00,000.00

export const getSalary = () => getData<SalaryView>(SALARY_PATH);
export const confirmSalary = () => putData<SalaryView>(SALARY_PATH, { action: "confirm" });
export const correctSalary = (c: SalaryCorrection) => putData<SalaryView>(SALARY_PATH, { action: "correct", payer: c.payer, day: c.day, amount: c.amount });

/** Control, format (bidi overrides, zero-width), separator, surrogate, unassigned and private-use characters. */
const HIDDEN = /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}\p{Cn}\p{Co}]/u;
/** Plain digits with up to two decimals, as the API reads them (no sign, exponent or spaces). */
const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;

export type CorrectionErrors = Partial<Record<keyof SalaryCorrection, string>>;
export type CorrectionResult = { ok: true; value: SalaryCorrection } | { ok: false; errors: CorrectionErrors };

/** Checks what was typed into the form. Commas in the amount ("1,00,000") are dropped; anything else odd is refused. */
export function checkCorrection(raw: { payer: string; day: string; amount: string }): CorrectionResult {
  const errors: CorrectionErrors = {};
  const payer = raw.payer.trim();
  if (payer.length > MAX_PAYER) errors.payer = `Payer: at most ${MAX_PAYER} characters.`;
  else if (HIDDEN.test(payer)) errors.payer = "Payer: letters, digits and punctuation only.";
  const dayText = raw.day.trim();
  const day = Number(dayText);
  if (!/^\d{1,2}$/.test(dayText) || day < 1 || day > 31) errors.day = "Day of month: a whole number from 1 to 31.";
  const amount = raw.amount.trim().replaceAll(",", "");
  if (!AMOUNT.test(amount)) errors.amount = "Amount: a rupee amount like 85000 or 85000.50.";
  else if (toPaise(amount) <= 0n) errors.amount = "Amount: more than zero.";
  else if (toPaise(amount) > MAX_AMOUNT_PAISE) errors.amount = "Amount: at most ₹1,00,00,00,000.";
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, value: { payer: payer === "" ? null : payer, day, amount } };
}

/** 1 → "1st", 2 → "2nd", 11 → "11th", 23 → "23rd". */
export function ordinal(n: number): string {
  const tens = n % 100;
  if (tens >= 11 && tens <= 13) return `${n}th`;
  return `${n}${({ 1: "st", 2: "nd", 3: "rd" } as Record<number, string>)[n % 10] ?? "th"}`;
}

export const CHOICE_TEXT: Record<SalaryChoiceKind, string> = { confirmed: "Confirmed by you", corrected: "Corrected by you" };

/** The forecast's salary source (AOS-722 API: `salary.source`): the small badge the card shows, or none. */
export const salarySourceBadge = (source: "detected" | "confirmed" | null | undefined) => (source === "confirmed" ? "confirmed" : null);
