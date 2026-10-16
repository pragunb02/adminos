import { http } from "msw";
import { setupServer } from "msw/node";
import { ok } from "./envelope";

/**
 * One MSW server for all unit tests; each test adds its own handlers with `server.use(...)`.
 * Defaults cover calls every page makes through the shell (the Review badge), so page tests stay focused.
 */
export const server = setupServer(http.get("/api/v1/transactions/suggestions", () => ok([])));
