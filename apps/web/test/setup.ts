import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, vi } from "vitest";
import { server } from "./msw/server";
import { nav } from "./next";

// Any request without a handler is a test bug, not a silent pass.
beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  nav.reset();
});
afterAll(() => server.close());

vi.mock("next/navigation", async () => (await import("./next")).navigationMock);
vi.mock("next/link", async () => (await import("./next")).linkMock);
