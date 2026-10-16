import { setupServer } from "msw/node";

/** One MSW server for all unit tests; each test adds its own handlers with `server.use(...)`. */
export const server = setupServer();
