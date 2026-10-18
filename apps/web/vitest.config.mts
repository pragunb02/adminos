import path from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Web unit/component tests (AOS-310). Browser end-to-end tests are Playwright (AOS-311, `e2e/`).
export default defineConfig({
  plugins: [react()],
  resolve: { alias: { "@": path.resolve(import.meta.dirname, "src") } },
  test: {
    environment: "jsdom",
    environmentOptions: { jsdom: { url: "http://localhost:3000/" } },
    setupFiles: ["./test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}", "test/**/*.test.{ts,tsx}"],
    restoreMocks: true,
    // Above test/setup.ts's 5 s async-util timeout, so a slow findBy fails as itself, not as a test timeout (AOS-642).
    testTimeout: 15_000,
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.{ts,tsx}", "src/lib/types.ts"],
      reporter: ["text", "text-summary", "html", "json-summary"],
      reportsDirectory: "coverage",
      // Pragun, 2026-10-16 (D-47): the bar for every PR.
      thresholds: { lines: 90, statements: 90, branches: 85, functions: 85 },
    },
  },
});
