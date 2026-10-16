import path from "node:path";
import nextEnv from "@next/env";

// Single source of configuration: the repo-root .env (shared with docker compose).
nextEnv.loadEnvConfig(path.resolve(import.meta.dirname, "../.."));

// Where the Next server forwards /api/* (D-32 same-origin proxy). Resolved at build time.
const API_INTERNAL_URL = process.env.API_INTERNAL_URL || "http://localhost:8080";

/** @type {import('next').NextConfig} */
const nextConfig = {
  env: {
    // Public OAuth client id (not a secret), inlined into the login page.
    NEXT_PUBLIC_GOOGLE_CLIENT_ID: process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID || process.env.GOOGLE_CLIENT_ID || "",
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${API_INTERNAL_URL}/api/:path*` }];
  },
};

export default nextConfig;
