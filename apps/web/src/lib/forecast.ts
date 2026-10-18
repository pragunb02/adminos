/**
 * Month-end forecast API types (AOS-714). All money is an exact decimal string (D-30); every number
 * comes from the API's deterministic forecast, the page only formats and labels it (D-67).
 */
export interface ForecastDue {
  name: string;
  date: string;
  amount: string;
  overdue: boolean;
  /** Projected bills only (AOS-721): the bill's type, how many months running it was billed, and since when ("YYYY-MM"). */
  billType?: string | null;
  monthsSeen?: number | null;
  seenFrom?: string | null;
}

/** `projected_bills` (AOS-721): regular monthly bills that haven't arrived yet this month, at their usual amount and day. */
export type ForecastPartKind = "bills" | "projected_bills" | "renewals" | "mandates" | "everyday";

export interface ForecastPart {
  kind: ForecastPartKind;
  amount: string;
  items: ForecastDue[];
  weekdayRate: string | null;
  weekendRate: string | null;
  weekdays: number | null;
  weekendDays: number | null;
  split: boolean | null;
  method: string | null;
  learntFrom: string | null;
  learntTo: string | null;
}

export interface ForecastRange { low: string; high: string; level: number; months: number }

export interface MonthEnd {
  month: string;
  spentSoFar: string;
  total: string;
  daysLeft: number;
  parts: ForecastPart[];
  range: ForecastRange | null;
  rangeMonthsNeeded: number;
}

export interface UntilSalary {
  from: string | null;
  to: string | null;
  spentSince: string;
  expected: string;
  left: string;
  leftLow: string | null;
  leftHigh: string | null;
  parts: ForecastPart[];
}

export type SalaryStatus = "expected" | "late" | "unknown";
/** `not_seen` (AOS-722): the payer the owner chose has sent no credit lately. */
export type NoSalaryReason = "no_regular_credit" | "amount_varies" | "date_varies" | "stopped" | "not_seen";

export interface SalaryOutlook {
  status: SalaryStatus;
  reason: NoSalaryReason | null;
  payer: string | null;
  expectedOn: string | null;
  typicalAmount: string | null;
  lastAmount: string | null;
  lastReceivedOn: string | null;
  credits: number | null;
  untilSalary: UntilSalary | null;
  /** AOS-722: "confirmed" when the owner's stored choice was used instead of detection; and its usual day. */
  source?: "detected" | "confirmed";
  usualDay?: number | null;
}

export interface ForecastData {
  status: "ready" | "not_enough_history";
  today: string;
  history: { days: number; neededDays: number; readyOn: string | null } | null;
  monthEnd: MonthEnd | null;
  salary: SalaryOutlook | null;
}

export const FORECAST_PATH = "/api/v1/forecast";

/** What each part is called on the card. */
export const PART_LABEL: Record<ForecastPartKind, string> = {
  bills: "Bills",
  projected_bills: "Bills not arrived yet",
  renewals: "Renewals",
  mandates: "AutoPay & mandates",
  everyday: "Everyday spending",
};

/** Why no salary is shown, in plain words. */
export const NO_SALARY_TEXT: Record<NoSalaryReason, string> = {
  no_regular_credit: "No regular monthly salary found in your credits yet.",
  amount_varies: "Your monthly credits vary too much to plan on.",
  date_varies: "Your monthly credits come on days too far apart to plan on.",
  stopped: "Your regular credit hasn't come for a while.",
  not_seen: "We haven't seen a credit from your salary payer yet.",
};

const DAY = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "UTC" });
const WEEKDAY_DAY = new Intl.DateTimeFormat("en-IN", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" });

/** "2026-10-31" → "31 Oct". */
export const shortDate = (d: string) => DAY.format(new Date(`${d}T00:00:00Z`));
/** "2026-11-01" → "Sun, 1 Nov". */
export const dayAndDate = (d: string) => WEEKDAY_DAY.format(new Date(`${d}T00:00:00Z`));
/** The last day of a "YYYY-MM" month, as "YYYY-MM-DD". */
export const monthEndDate = (month: string) => {
  const [y, m] = month.split("-").map(Number);
  return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10);
};
