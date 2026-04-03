// ── API Envelope ──

export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  error: { code: string; message: string; details?: Record<string, unknown> } | null;
  pagination?: { cursor: string | null; hasMore: boolean; total: number };
  meta: { requestId: string; timestamp: string };
}

// ── Dashboard ──

export interface DashboardHome {
  greeting: string;
  summary: DashboardSummary;
  actionItems: ActionItem[];
  latestBriefing: BriefingSummary | null;
}

export interface DashboardSummary {
  totalSpentThisWeek: number;
  activeSubscriptions: number;
  flaggedSubscriptions: number;
  upcomingBills: number;
  openAnomalies: number;
}

export interface ActionItem {
  id: string;
  type: "anomaly" | "subscription" | "bill" | "briefing";
  title: string;
  description: string;
  severity: "info" | "warning" | "critical";
  entityId: string;
  actionLabel: string;
}

export interface BriefingSummary {
  id: string;
  periodStart: string;
  periodEnd: string;
  content: string;
  totalSpent: number | null;
}

// ── Transactions ──

export interface Transaction {
  id: string;
  type: string;
  amount: number;
  currency: string;
  merchantName: string | null;
  category: string;
  subcategory: string | null;
  categorySource: string;
  sourceType: string;
  accountLast4: string | null;
  paymentMethod: string | null;
  isRecurring: boolean;
  isAnomaly: boolean;
  transactedAt: string;
}

// ── Subscriptions ──

export interface Subscription {
  id: string;
  name: string;
  merchantName: string | null;
  category: string;
  amount: number;
  currency: string;
  billingCycle: string;
  nextBillingDate: string | null;
  lastBilledDate: string | null;
  firstBilledDate: string | null;
  status: string;
  priceChanged: boolean;
  priceChangePct: number | null;
  isFlagged: boolean;
  flaggedReason: string | null;
  transactionIds: string[];
  createdAt: string;
}

export interface SubscriptionSummary {
  totalMonthlyCost: number;
  currency: string;
  activeCount: number;
  flaggedCount: number;
  cancelledCount: number;
  byCategory: Record<string, { count: number; monthly: number }>;
}

// ── Bills ──

export interface Bill {
  id: string;
  billType: string;
  billerName: string;
  amount: number;
  minimumDue: number | null;
  currency: string;
  dueDate: string;
  status: string;
  paidAt: string | null;
  paidAmount: number | null;
  paymentTxnId: string | null;
  createdAt: string;
}

// ── Briefings ──

export interface Briefing {
  id: string;
  periodStart: string;
  periodEnd: string;
  type: string;
  content: string;
  totalSpent: number | null;
  totalIncome: number | null;
  topCategories: { category: string; amount: number }[] | null;
  subscriptionsFlagged: number;
  anomaliesDetected: number;
  billsUpcoming: number;
  status: string;
  insights: Insight[] | null;
  createdAt: string;
}

export interface Insight {
  id: string;
  type: string;
  title: string;
  body: string;
  severity: string;
  actionType: string;
  entityId: string | null;
  status: string;
}

// ── Anomalies ──

export interface Anomaly {
  id: string;
  transactionId: string;
  type: string;
  confidenceScore: number;
  reason: string;
  agentExplanation: string | null;
  status: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
  createdAt: string;
}

// ── Settings ──

export interface UserConnection {
  id: string;
  sourceType: string;
  status: string;
  lastSyncedAt: string | null;
  totalSynced: number;
  lastSyncStatus: string | null;
  createdAt: string;
}

export interface NotificationPreferences {
  weeklyBriefing: boolean;
  briefingDay: string;
  briefingTime: string;
  billReminders: boolean;
  billReminderDays: number[];
  anomalyAlerts: boolean;
  subscriptionFlags: boolean;
  emailNotifications: boolean;
  pushNotifications: boolean;
  quietHoursStart: string;
  quietHoursEnd: string;
}

export interface Session {
  id: string;
  ipAddress: string | null;
  userAgent: string | null;
  lastActiveAt: string | null;
  createdAt: string;
}

// ── Sync ──

export interface SyncSession {
  id: string;
  status: string;
  totalItems: number;
  processedItems: number;
  duplicateItems: number;
  netNewItems: number;
  failedItems: number;
  startedAt: string | null;
}

// ── Auth ──

export interface AuthResponse {
  refreshToken: string;
  expiresIn: number;
  user: {
    id: string;
    email: string;
    name: string;
    onboardingStatus: string;
    role: string;
  };
}
