import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, vi } from "vitest";
import { server } from "./msw/server";
import { nav } from "./next";

// findBy*/waitFor give up after 1 s by default. On a loaded machine or CI runner (many agents, coverage on) a page
// that renders correctly can take longer than that to settle, and unrelated tests failed at random (AOS-642).
// 5 s still fails a really missing element quickly; vitest.config.mts raises the per-test timeout to match.
configure({ asyncUtilTimeout: 5_000 });

// Any request without a handler is a test bug, not a silent pass.
beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  nav.reset();
  // A test that forgot to restore fake timers would stall every later findBy in the file (AOS-642).
  vi.useRealTimers();
});
afterAll(() => server.close());

// Recharts' ResponsiveContainer measures its parent; jsdom has no layout, so give it a size.
class ResizeObserverStub {
  constructor(private cb: ResizeObserverCallback) {}
  observe(el: Element) { this.cb([{ target: el, contentRect: { width: 600, height: 160 } } as unknown as ResizeObserverEntry], this as unknown as ResizeObserver); }
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;

vi.mock("next/navigation", async () => (await import("./next")).navigationMock);
vi.mock("next/link", async () => (await import("./next")).linkMock);
