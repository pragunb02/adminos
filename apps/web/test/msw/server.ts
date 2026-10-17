import { http } from "msw";
import { setupServer } from "msw/node";
import { ok } from "./envelope";
import { fx } from "../fixtures";
import { cx } from "../coachFixtures";

/**
 * One MSW server for all unit tests; each test adds its own handlers with `server.use(...)`.
 * Defaults cover calls every page makes through the shell (the Review badge), so page tests stay focused.
 */
export const server = setupServer(
  http.get("/api/v1/transactions/suggestions", () => ok([])),
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
  http.get("/api/v1/coach/settings", () => ok({ limitAlerts: true })),
  http.get("/api/v1/coach/lenses", () => ok(cx.lenses())),
  // Review → Check these categories (AOS-708): nothing to ask, learning off, no local test set unless a test says otherwise.
  http.get("/api/v1/categories/review", () => ok([])),
  http.get("/api/v1/categories/model", () => ok({ enabled: false, state: "off", corrections: 0, correctionsNeeded: 20 })),
  http.get("/api/v1/categories/golden", () => ok([])),
);
