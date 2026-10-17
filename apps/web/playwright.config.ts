import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests (AOS-311, D-47). Two servers, both on 127.0.0.1 only:
 * - the API in in-memory mode (for the full-stack project), with a throwaway test secret;
 * - the production Next build, proxying /api/* to that API.
 * Mocked projects answer /api/* in the browser from shared fixtures, so every branch is reachable.
 */
// A throwaway secret for the test API only. The whole secret is the HMAC key (AOS-607).
export const E2E_JWT_SECRET = "e2e-only-secret-not-for-production-0123456789";
const WEB = "http://127.0.0.1:3100";
const API = "http://127.0.0.1:18080";
const CI = !!process.env.CI;

export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: { baseURL: WEB, trace: "retain-on-failure", screenshot: "only-on-failure" },
  projects: [
    { name: "desktop-chrome", use: { ...devices["Desktop Chrome"] }, testIgnore: /full-stack/ },
    { name: "iphone-safari", use: { ...devices["iPhone 15"] }, testIgnore: /full-stack/ },
    { name: "android-chrome", use: { ...devices["Pixel 7"] }, testIgnore: /full-stack/ },
    // security.spec.ts runs in every project: its real-API attacks only here, its mocked ones only in the others (AOS-619).
    { name: "full-stack", use: { ...devices["Desktop Chrome"] }, testMatch: /full-stack|security\.spec/ },
  ],
  webServer: [
    {
      command: "cd ../../services/api && ./gradlew --no-daemon -q run",
      url: `${API}/health`,
      env: { JWT_SECRET: E2E_JWT_SECRET, PORT: "18080", BIND_HOST: "127.0.0.1", DATABASE_URL: "" },
      reuseExistingServer: !CI,
      timeout: 240_000,
    },
    {
      command: "npx next build && npx next start -H 127.0.0.1 -p 3100",
      url: `${WEB}/login`,
      env: { API_INTERNAL_URL: API, NEXT_PUBLIC_GOOGLE_CLIENT_ID: "e2e-client-id" },
      reuseExistingServer: !CI,
      timeout: 300_000,
    },
  ],
});
