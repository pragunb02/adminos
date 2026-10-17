import type { SeriesPoint } from "@/lib/analytics";
import type { Anomaly, Bill, Briefing, DashboardHome, NotificationPreferences, Session, Subscription, SubscriptionSummary, Transaction, UserConnection } from "@/lib/types";

/** Canned API data for unit tests and Playwright mocks (AOS-310/311). Dates are fixed, IST-friendly. */
export const fx = {
  home: (over: Partial<DashboardHome> = {}): DashboardHome => ({
    greeting: "Good morning, Pragun",
    summary: { totalSpentThisWeek: "12500", activeSubscriptions: 4, flaggedSubscriptions: 1, upcomingBills: 2, openAnomalies: 1 },
    actionItems: [
      { id: "a1", type: "anomaly", title: "Unusual charge", description: "₹50,000 at 3am", severity: "critical", entityId: "an-1", actionLabel: "Review" },
      { id: "a2", type: "subscription", title: "Unused Netflix", description: "No logins in 60 days", severity: "warning", entityId: "s-1", actionLabel: "Review sub" },
      { id: "a3", type: "bill", title: "HDFC card bill", description: "Due in 2 days", severity: "warning", entityId: "b-1", actionLabel: "View bill" },
      { id: "a4", type: "briefing", title: "Weekly briefing", description: "Ready", severity: "info", entityId: "br-1", actionLabel: "Read" },
    ],
    latestBriefing: { id: "br-1", periodStart: "2026-10-05", periodEnd: "2026-10-11", content: "You spent less on food.", totalSpent: "18000" },
    ...over,
  }),
  bill: (over: Partial<Bill> = {}): Bill => ({
    id: "b-1", billType: "credit_card", billerName: "HDFC Card", amount: "24500", minimumDue: "1225", currency: "INR",
    dueDate: "2026-10-18", status: "upcoming", paidAt: null, paidAmount: null, paymentTxnId: null, createdAt: "2026-10-01T00:00:00Z", ...over,
  }),
  briefing: (over: Partial<Briefing> = {}): Briefing => ({
    id: "br-1", periodStart: "2026-10-05", periodEnd: "2026-10-11", type: "weekly", content: "You spent ₹18,000 this week.",
    totalSpent: "18000", totalIncome: "100000", topCategories: [{ category: "food", amount: "6000" }], subscriptionsFlagged: 1,
    anomaliesDetected: 1, billsUpcoming: 2, status: "generated", insights: [], createdAt: "2026-10-12T03:00:00Z", ...over,
  }),
  subscription: (over: Partial<Subscription> = {}): Subscription => ({
    id: "s-1", name: "Netflix", merchantName: "Netflix", category: "entertainment", amount: "649", currency: "INR", billingCycle: "monthly",
    nextBillingDate: "2026-10-25", lastBilledDate: "2026-09-25", firstBilledDate: "2025-01-25", status: "active", priceChanged: false,
    priceChangePct: null, isFlagged: false, flaggedReason: null, wasteScore: null, wasteReasons: [], transactionIds: [], ...over,
  } as Subscription),
  subscriptionSummary: (over: Partial<SubscriptionSummary> = {}): SubscriptionSummary => ({
    totalMonthlyCost: "1847", currency: "INR", activeCount: 4, flaggedCount: 1, cancelledCount: 2, byCategory: {}, ...over,
  }),
  transaction: (over: Partial<Transaction> = {}): Transaction => ({
    id: "t-1", type: "debit", nature: "spend", amount: "349", currency: "INR", merchantName: "Swiggy", category: "food", subcategory: null,
    categorySource: "rules", sourceType: "sms", accountLast4: "1234", paymentMethod: "upi", isRecurring: false, isAnomaly: false,
    transactedAt: "2026-10-15T08:30:00Z", ...over,
  }),
  anomaly: (over: Partial<Anomaly> = {}): Anomaly => ({
    id: "an-1", transactionId: "t-9", type: "large_amount", confidenceScore: 0.92, reason: "3.4× your usual shopping spend",
    agentExplanation: "This is much larger than usual.", status: "open", resolvedAt: null, resolvedBy: null, createdAt: "2026-10-15T21:00:00Z", ...over,
  }),
  connection: (over: Partial<UserConnection> = {}): UserConnection => ({
    id: "c-1", sourceType: "gmail", status: "connected", lastSyncedAt: "2026-10-15T10:00:00Z", totalSynced: 120, lastSyncStatus: "success",
    createdAt: "2026-09-01T00:00:00Z", ...over,
  }),
  prefs: (over: Partial<NotificationPreferences> = {}): NotificationPreferences => ({
    weeklyBriefing: true, briefingDay: "mon", briefingTime: "09:00", billReminders: true, billReminderDays: [3, 1], anomalyAlerts: true,
    subscriptionFlags: true, emailNotifications: true, pushNotifications: false, quietHoursStart: "22:00", quietHoursEnd: "08:00", ...over,
  }),
  session: (over: Partial<Session> = {}): Session => ({
    id: "se-1", ipAddress: "127.0.0.1", userAgent: "Safari on Mac", lastActiveAt: "2026-10-16T05:00:00Z", createdAt: "2026-10-16T04:00:00Z", ...over,
  }),
  /** Analytics (AOS-301 shapes): October 2026 so far vs the same days of September. */
  summary: (spent = "48250.00", received = "100000.00", over: Record<string, unknown> = {}) => ({
    period: { from: "2026-10-01", to: "2026-10-16" },
    totals: { spent, received, net: (Number(received) - Number(spent)).toFixed(2) },
    spendCount: 42, previousPeriod: { from: "2026-09-15", to: "2026-09-30" },
    previous: { spent: "44050.00", received: "0.00", net: "-44050.00" }, spentChangePct: "9.5", ...over,
  }),
  week: (): { period: { from: string; to: string }; bucket: string; points: SeriesPoint[] } => ({
    period: { from: "2026-10-12", to: "2026-10-18" }, bucket: "day",
    points: [
      { start: "2026-10-12", spent: "1200.00", received: "0.00" }, { start: "2026-10-13", spent: "350.50", received: "0.00" },
      { start: "2026-10-14", spent: "0.00", received: "0.00" }, { start: "2026-10-15", spent: "2999.00", received: "0.00" },
      { start: "2026-10-16", spent: "350.00", received: "0.00" }, { start: "2026-10-17", spent: "0.00", received: "0.00" },
      { start: "2026-10-18", spent: "0.00", received: "0.00" },
    ],
  }),
  categories: () => ({
    period: { from: "2026-10-01", to: "2026-10-16" },
    categories: [
      { category: "shopping", spent: "18400.00", share: 0.3813, count: 6, previousSpent: "16300.00" },
      { category: "food", spent: "11200.00", share: 0.2321, count: 18, previousSpent: "12000.00" },
      { category: "transport", spent: "4300.00", share: 0.0891, count: 9, previousSpent: "4300.00" },
      { category: "utilities", spent: "3100.00", share: 0.0642, count: 3, previousSpent: "2800.00" },
      { category: "health", spent: "1250.00", share: 0.0259, count: 2, previousSpent: "0.00" },
      { category: "education", spent: "999.00", share: 0.0207, count: 1, previousSpent: "0.00" },
    ],
  }),
  merchants: () => ({
    period: { from: "2026-10-01", to: "2026-10-16" },
    merchants: [
      { merchant: "Amazon", spent: "9999.00", count: 4, category: "shopping" },
      { merchant: "Swiggy", spent: "6120.00", count: 18, category: "food" },
      { merchant: "Uber", spent: "2100.00", count: 1, category: "transport" },
    ],
  }),
  /** Every day of October so far (sums to ₹48,250, exact; day 9 is refund-only). */
  monthSoFar: () => {
    const days = ["100.10", "200.20", "4500.00", "1200.00", "2000.00", "3149.70", "800.00", "6000.00", "-300.00", "5000.00", "4200.00", "3500.00", "2800.00", "9000.00", "4000.00", "2100.00"];
    return { period: { from: "2026-10-01", to: "2026-10-16" }, bucket: "day", points: days.map((spent, i) => ({ start: `2026-10-${String(i + 1).padStart(2, "0")}`, spent, received: "0.00" })) };
  },
  /** All 30 days of September. */
  lastMonth: () => ({
    period: { from: "2026-09-01", to: "2026-09-30" }, bucket: "day",
    points: Array.from({ length: 30 }, (_, i) => ({ start: `2026-09-${String(i + 1).padStart(2, "0")}`, spent: "2000.00", received: "0.00" })),
  }),
  /** A suggested pair for Review (AOS-309): the same payment seen by SMS and by the statement. */
  suggestion: (over: Record<string, unknown> = {}) => ({
    id: "sg-1", relation: "duplicate", score: 9, reason: "same amount and reference",
    primary: fx.transaction({ id: "sg-1-p", sourceType: "sms" }),
    related: fx.transaction({ id: "sg-1-r", merchantName: "SWIGGY BLR", sourceType: "statement" }),
    ...over,
  }),
  /** A due item for the dues calendar (AOS-503). */
  due: (over: Partial<import("@/lib/types").DueItem> = {}): import("@/lib/types").DueItem => ({
    kind: "bill", id: "b-1", name: "Tata Power", amount: "2350.00", minimumDue: null, date: "2026-10-20",
    status: "upcoming", detail: "electricity", trial: false, ...over,
  }),
  /** October 2026 as the user sees it on the 16th. */
  duesMonth: (over: Partial<import("@/lib/types").DuesMonth> = {}): import("@/lib/types").DuesMonth => ({
    month: "2026-10", today: "2026-10-16",
    items: [
      fx.due(),
      fx.due({ kind: "renewal", id: "s-1", name: "Netflix", amount: "649.00", status: "expected", detail: "monthly" }),
      fx.due({ id: "b-2", name: "HDFC Credit Card", amount: "18450.00", minimumDue: "922.50", date: "2026-10-17", detail: "credit_card" }),
      fx.due({ id: "b-3", name: "Airtel", amount: "599.00", date: "2026-10-10", status: "paid", detail: "mobile" }),
    ].sort((a, b) => a.date.localeCompare(b.date)),
    overdue: [],
    totals: { open: "20800.00", paid: "599.00", renewals: "649.00" },
    ...over,
  }),
  /** A mandate from bank SMS (AOS-502). */
  mandate: (over: Partial<import("@/lib/types").Mandate> = {}): import("@/lib/types").Mandate => ({
    id: "m-1", payee: "BAJAJ FINANCE LTD", rail: "nach", amount: "5400.00", frequency: "monthly", status: "active",
    nextDebitOn: "2026-11-05", nextDebitAmount: "5400.00", lastDebitAt: "2026-10-05T04:00:00Z", lastDebitAmount: "5400.00",
    validUntil: "2030-03-05", dormant: false, hasReference: true, ...over,
  }),
};
