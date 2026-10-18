import { http } from "msw";
import { setupServer } from "msw/node";
import { ok } from "./envelope";
import { fx } from "../fixtures";
import { cx } from "../coachFixtures";
import { sx } from "../salaryFixtures";

/**
 * One MSW server for all unit tests; each test adds its own handlers with `server.use(...)`.
 * Defaults cover calls every page makes through the shell (the Review badge), so page tests stay focused.
 */
export const server = setupServer(
  http.get("/api/v1/transactions/suggestions", () => ok([])),
  // Review → messages read by AI (AOS-705): none unless a test says otherwise.
  http.get("/api/v1/review/messages", () => ok([])),
  // Review → formats to learn (AOS-706): none unless a test says otherwise.
  http.get("/api/v1/review/templates", () => ok([])),
  // Settings → Notifications on this device (AOS-510): push not set up unless a test says otherwise.
  http.get("/api/v1/push", () => ok({ enabled: false, publicKey: null, devices: [] })),
  // Settings → AI features (AOS-704): one feature, not set up on the server, unless a test says otherwise.
  http.get("/api/v1/ai/features", () => ok({ features: [fx.aiFeature({ available: false, provider: null, locality: null, model: null, destination: null, evaluation: null })], monthlyCapUsd: "2.00" })),
  // Subscriptions → AutoPay & mandates (AOS-502): none unless a test says otherwise.
  http.get("/api/v1/mandates", () => ok([])),
  // Home's "Where it leaks" card (AOS-902): nothing leaked unless a test says otherwise.
  http.get("/api/v1/coach/leaks", () => ok(cx.emptyLeaks())),
  // Limits and coach settings (AOS-904): still learning, no limits, alerts on.
  http.get("/api/v1/coach/limits", () => ok(cx.limits())),
  http.get("/api/v1/coach/settings", () => ok({ limitAlerts: true, eveningDigest: true, digestTime: "21:30", digestSkipZeroDay: false })),
  http.get("/api/v1/coach/lenses", () => ok(cx.lenses())),
  // Review's investments block (AOS-820): every holding adds up unless a test says otherwise.
  http.get("/api/v1/wealth/reconciliation", () => ok({ holdings: [], blocked: 0 })),
  // Goals (AOS-907): none unless a test says otherwise.
  http.get("/api/v1/coach/goals", () => ok([])),
  // Savings rate on Home (AOS-908): too little data unless a test says otherwise.
  http.get("/api/v1/coach/savings", () => ok(cx.noSavings())),
  // Review → pay-later gaps (AOS-909): nothing owed unless a test says otherwise.
  http.get("/api/v1/pay-later", () => ok({ outstanding: "0.00", currency: "INR", providers: [] })),
  // Splitwise (AOS-911): nothing imported unless a test says otherwise (Import and Review read these).
  http.get("/api/v1/splitwise/entries", () => ok([])),
  http.get("/api/v1/splitwise/summary", () => ok({ balances: [], counts: {} })),
  http.get("/api/v1/splitwise/imports", () => ok([])),
  // Review → Check these categories (AOS-708): nothing to ask, learning off, no local test set unless a test says otherwise.
  http.get("/api/v1/categories/review", () => ok([])),
  http.get("/api/v1/categories/model", () => ok({ enabled: false, state: "off", corrections: 0, correctionsNeeded: 20 })),
  http.get("/api/v1/categories/golden", () => ok([])),
  // Home → Month-end forecast (AOS-714): too little history unless a test says otherwise.
  http.get("/api/v1/forecast", () => ok({ status: "not_enough_history", today: "2026-10-16", history: { days: 40, neededDays: 90, readyOn: "2026-11-25" }, monthEnd: null, salary: null })),
  // Home → forecast → Your salary (AOS-722): detected, nothing chosen, unless a test says otherwise.
  http.get("/api/v1/forecast/salary", () => ok(sx.view())),
  // Bank balances (AOS-720): none known unless a test says otherwise.
  http.get("/api/v1/balances", () => ok({ today: "2026-10-16", accounts: [], total: "0.00", countedAccounts: 0, maxAgeDays: 7 })),
  // Review → Same merchant? (AOS-709): nothing to ask unless a test says otherwise.
  http.get("/api/v1/merchants/merge-proposals", () => ok([])),
);
