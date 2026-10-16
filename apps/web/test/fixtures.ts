import type { Anomaly, Bill, Briefing, DashboardHome, NotificationPreferences, Session, Subscription, SubscriptionSummary, Transaction, UserConnection } from "@/lib/types";

/** Canned API data for unit tests and Playwright mocks (AOS-310/311). Dates are fixed, IST-friendly. */
export const fx = {
  home: (over: Partial<DashboardHome> = {}): DashboardHome => ({
    greeting: "Good morning, Pragun",
    summary: { totalSpentThisWeek: 12500, activeSubscriptions: 4, flaggedSubscriptions: 1, upcomingBills: 2, openAnomalies: 1 },
    actionItems: [
      { id: "a1", type: "anomaly", title: "Unusual charge", description: "₹50,000 at 3am", severity: "critical", entityId: "an-1", actionLabel: "Review" },
      { id: "a2", type: "subscription", title: "Unused Netflix", description: "No logins in 60 days", severity: "warning", entityId: "s-1", actionLabel: "Review sub" },
      { id: "a3", type: "bill", title: "HDFC card bill", description: "Due in 2 days", severity: "warning", entityId: "b-1", actionLabel: "View bill" },
      { id: "a4", type: "briefing", title: "Weekly briefing", description: "Ready", severity: "info", entityId: "br-1", actionLabel: "Read" },
    ],
    latestBriefing: { id: "br-1", periodStart: "2026-10-05", periodEnd: "2026-10-11", content: "You spent less on food.", totalSpent: 18000 },
    ...over,
  }),
  bill: (over: Partial<Bill> = {}): Bill => ({
    id: "b-1", billType: "credit_card", billerName: "HDFC Card", amount: 24500, minimumDue: 1225, currency: "INR",
    dueDate: "2026-10-18", status: "upcoming", paidAt: null, paidAmount: null, paymentTxnId: null, createdAt: "2026-10-01T00:00:00Z", ...over,
  }),
  briefing: (over: Partial<Briefing> = {}): Briefing => ({
    id: "br-1", periodStart: "2026-10-05", periodEnd: "2026-10-11", type: "weekly", content: "You spent ₹18,000 this week.",
    totalSpent: 18000, totalIncome: 100000, topCategories: [{ category: "food", amount: 6000 }], subscriptionsFlagged: 1,
    anomaliesDetected: 1, billsUpcoming: 2, status: "generated", insights: [], createdAt: "2026-10-12T03:00:00Z", ...over,
  }),
  subscription: (over: Partial<Subscription> = {}): Subscription => ({
    id: "s-1", name: "Netflix", merchantName: "Netflix", category: "entertainment", amount: 649, currency: "INR", billingCycle: "monthly",
    nextBillingDate: "2026-10-25", lastBilledDate: "2026-09-25", firstBilledDate: "2025-01-25", status: "active", priceChanged: false,
    priceChangePct: null, isFlagged: false, flaggedReason: null, transactionIds: [], ...over,
  } as Subscription),
  subscriptionSummary: (over: Partial<SubscriptionSummary> = {}): SubscriptionSummary => ({
    totalMonthlyCost: 1847, currency: "INR", activeCount: 4, flaggedCount: 1, cancelledCount: 2, byCategory: {}, ...over,
  }),
  transaction: (over: Partial<Transaction> = {}): Transaction => ({
    id: "t-1", type: "debit", amount: 349, currency: "INR", merchantName: "Swiggy", category: "food", subcategory: null,
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
};
