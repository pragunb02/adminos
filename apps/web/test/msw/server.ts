import { http } from "msw";
import { setupServer } from "msw/node";
import { ok } from "./envelope";

/**
 * One MSW server for all unit tests; each test adds its own handlers with `server.use(...)`.
 * Defaults cover calls every page makes through the shell (the Review badge), so page tests stay focused.
 */
export const server = setupServer(
  http.get("/api/v1/transactions/suggestions", () => ok([])),
  // Review → messages read by AI (AOS-705): none unless a test says otherwise.
  http.get("/api/v1/review/messages", () => ok([])),
  // Settings → Notifications on this device (AOS-510): push not set up unless a test says otherwise.
  http.get("/api/v1/push", () => ok({ enabled: false, publicKey: null, devices: [] })),
  // Subscriptions → AutoPay & mandates (AOS-502): none unless a test says otherwise.
  http.get("/api/v1/mandates", () => ok([])),
);
