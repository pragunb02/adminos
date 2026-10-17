import path from "node:path";
import nextEnv from "@next/env";

// Single source of configuration: the repo-root .env (shared with docker compose).
nextEnv.loadEnvConfig(path.resolve(import.meta.dirname, "../.."));

// Where the Next server forwards /api/* (D-32 same-origin proxy). Resolved at build time.
const API_INTERNAL_URL = process.env.API_INTERNAL_URL || "http://localhost:8080";
const HTTPS = (process.env.APP_URL || "").startsWith("https://");

/**
 * Hardening headers on every response, static files included (AOS-618, AUD-07). The per-response CSP with its
 * script nonce is set by src/proxy.ts; API responses carry the API's own headers.
 */
export const SECURITY_HEADERS = [
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "no-referrer" },
  {
    key: "Permissions-Policy",
    value: "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=(), browsing-topics=()",
  },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
  { key: "Cross-Origin-Resource-Policy", value: "same-origin" },
  ...(HTTPS ? [{ key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" }] : []),
];

/** @type {import('next').NextConfig} */
const nextConfig = {
  poweredByHeader: false,
  env: {
    // Public OAuth client id (not a secret), inlined into the login page.
    NEXT_PUBLIC_GOOGLE_CLIENT_ID: process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID || process.env.GOOGLE_CLIENT_ID || "",
  },
  async headers() {
    // Not on /api/*: the API sets its own (stricter) headers and they pass through the rewrite.
    return [
      { source: "/", headers: SECURITY_HEADERS },
      { source: "/:path((?!api/).*)", headers: SECURITY_HEADERS },
    ];
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${API_INTERNAL_URL}/api/:path*` }];
  },
};

export default nextConfig;
