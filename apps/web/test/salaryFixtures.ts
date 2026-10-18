import type { SalaryChoice, SalaryView } from "@/lib/salary";

/** Confirm or correct the salary (AOS-722): fake payers only. */
export const sx = {
  detected: (over: Partial<NonNullable<SalaryView["detected"]>> = {}): NonNullable<SalaryView["detected"]> => ({
    payer: "ACME TECHNOLOGIES", usualDay: 30, typicalAmount: "100000.00", lastAmount: "100000.00",
    lastReceivedOn: "2026-09-30", expectedOn: "2026-10-30", credits: 6, ...over,
  }),
  choice: (over: Partial<SalaryChoice> = {}): SalaryChoice => ({
    payer: "Example Employer Pvt Ltd", day: 1, amount: "85000.00", kind: "corrected", updatedAt: "2026-10-18T05:00:00Z", ...over,
  }),
  /** What detection found and nothing chosen yet. */
  view: (over: Partial<SalaryView> = {}): SalaryView => ({ detected: sx.detected(), detectedReason: null, choice: null, ...over }),
};
