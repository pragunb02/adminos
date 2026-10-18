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
  totalSpentThisWeek: string; // exact decimal (D-30)
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
  totalSpent: string | null;
}

// ── Transactions ──

export interface Transaction {
  id: string;
  type: string;
  /** spend / income / transfer / refund / reversal: what it counts as (D-39). */
  nature: string;
  amount: string; // exact decimal (D-30)
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
  amount: string;
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
  /** 0–1, null until scored (AOS-509); 0.70 or more flags it. */
  wasteScore: number | null;
  /** Why the waste score is what it is, strongest first. */
  wasteReasons: string[];
  transactionIds: string[];
  createdAt: string;
}

export interface SubscriptionSummary {
  totalMonthlyCost: string;
  currency: string;
  activeCount: number;
  flaggedCount: number;
  cancelledCount: number;
  byCategory: Record<string, { count: number; monthly: string }>;
}

// ── Bills ──

/** One thing to pay on a day: a bill, or an expected subscription renewal (AOS-503, GET /api/v1/dues). */
export interface DueItem {
  kind: "bill" | "renewal" | "mandate";
  id: string;
  name: string;
  amount: string;
  minimumDue: string | null;
  date: string;
  /** Bills: upcoming | due | overdue | paid. Renewals: expected. */
  status: string;
  /** Bill type (credit_card…) or billing cycle (monthly…). */
  detail: string;
  trial: boolean;
}

/** A UPI AutoPay / e-NACH / card standing instruction, learnt from bank SMS (AOS-502, GET /api/v1/mandates). */
export interface Mandate {
  id: string;
  payee: string;
  rail: "upi_autopay" | "nach" | "card_si" | "unknown";
  amount: string | null;
  frequency: string;
  status: "active" | "paused" | "revoked" | "expired" | "completed";
  nextDebitOn: string | null;
  nextDebitAmount: string | null;
  lastDebitAt: string | null;
  lastDebitAmount: string | null;
  validUntil: string | null;
  /** Active and periodic, but nothing taken for two cycles. */
  dormant: boolean;
  hasReference: boolean;
}

/** A file in the documents vault (AOS-507): stored encrypted; never the storage key or file key. */
export interface VaultDocument {
  id: string;
  title: string;
  category: DocumentCategory;
  fileType: "pdf" | "png" | "jpeg" | "webp" | "heic";
  mime: string;
  sizeBytes: number;
  originalName: string | null;
  expiresOn: string | null;
  notes: string | null;
  createdAt: string;
}

export type DocumentCategory = "identity" | "tax" | "insurance" | "bank" | "property" | "vehicle" | "medical" | "warranty" | "other";

export interface DuesMonth {
  month: string;
  /** The user's own date (the server decides it). */
  today: string;
  items: DueItem[];
  overdue: DueItem[];
  totals: { open: string; paid: string; renewals: string };
}

export interface Bill {
  id: string;
  billType: string;
  billerName: string;
  amount: string;
  minimumDue: string | null;
  currency: string;
  dueDate: string;
  status: string;
  paidAt: string | null;
  paidAmount: string | null;
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
  totalSpent: string | null;
  totalIncome: string | null;
  topCategories: { category: string; amount: string }[] | null;
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
  /** The connected mailbox (Gmail only). */
  gmailAddress?: string | null;
  /** Why the last sync failed or the connection is in error; short and content-free (AOS-406). */
  lastError?: string | null;
  /** Until when Gmail push is registered (AOS-405); null when it isn't. */
  watchExpiresAt?: string | null;
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
  /** A coarse name such as "Chrome on Mac" (AOS-620). */
  device?: string;
  /** The session making this request. */
  current?: boolean;
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
  expiresIn: number;
  user: {
    id: string;
    email: string;
    name: string;
    onboardingStatus: string;
    role: string;
  };
  /** The passkey step this new session must take (AOS-617). */
  secondFactor?: "enrol" | "verify" | "done";
}

// ── Category review (AOS-707/708) ──

/** A model's guess to confirm or correct: by the local learned categoriser or the AI fallback. */
export interface CategoryGuess {
  category: string;
  /** 0–1, calibrated. */
  confidence: number;
  by: "learned" | "ai";
  /** true: it set the category; false: only a suggestion (the row is still Other). */
  applied: boolean;
  cues: string[];
  reason?: string | null;
  /** The next most likely categories, for one-tap corrections. */
  alternatives: string[];
}

/** "Check this category": ordered by the API by uncertainty × amount. */
export interface CategoryQuestion {
  transaction: Transaction;
  guess: CategoryGuess;
  priority: number;
}

export interface CategoryAnswer {
  transaction: Transaction;
  remembered: boolean;
  relabelled: number;
  golden?: { outcome: "added" | "already_there" | "refused"; reason?: string | null } | null;
}

/** The learned categoriser's status (counts only). */
export interface CategoryModelStatus {
  enabled: boolean;
  state: "off" | "cold_start" | "ready" | "corrupt";
  mode?: "apply" | "suggest" | null;
  trainedAt?: string | null;
  corrections: number;
  correctionsNeeded: number;
}

/** One scrubbed row of the user's local golden set. */
export interface GoldenRow {
  id: string;
  goldenId: string;
  merchantRaw: string;
  paymentMethod?: string | null;
  direction: string;
  category: string;
  createdAt: string;
}

// ── Merchant grouping (AOS-709) ──

/** "Are these one merchant?": the name a merge keeps and every name in the group, the kept one first. */
export interface MergeProposal {
  id: string;
  name: string;
  names: { name: string; payments: number }[];
  /** Why they look like one merchant, in plain words. */
  reasons: string[];
  score: number;
}
