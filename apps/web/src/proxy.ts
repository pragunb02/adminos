import { NextRequest, NextResponse } from "next/server";

/**
 * Pages anyone may open, matched **exactly** (AOS-619, AUD-13): a prefix match let `/login-anything` or
 * `/healthz` through. The guard is a navigation aid, not the security boundary (the API authenticates every call).
 */
const PUBLIC_PATHS = new Set(["/login", "/auth/callback", "/health"]);

/**
 * Static files a browser fetches without the session cookie (AOS-510): the manifest is requested
 * without credentials, and the service worker re-checks itself even after a session expires.
 * They contain no data.
 */
const PUBLIC_FILES = new Set(["/manifest.webmanifest", "/sw.js", "/icon-192.png", "/icon-512.png", "/apple-icon.png"]);

/**
 * The page's Content-Security-Policy (AOS-618, AUD-07). Scripts run only with this response's nonce (and what
 * those scripts load: `strict-dynamic`), never inline handlers or injected tags: no `unsafe-inline` for scripts.
 * Styles allow inline attributes (charts set `style=`), which can't run code. Nothing may frame the app.
 */
export function contentSecurityPolicy(nonce: string, opts: { dev: boolean; https: boolean }): string {
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${opts.dev ? " 'unsafe-eval'" : ""}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    `connect-src 'self'${opts.dev ? " ws:" : ""}`,
    "worker-src 'self'",
    "manifest-src 'self'",
    "frame-src 'none'",
    "frame-ancestors 'none'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    ...(opts.https ? ["upgrade-insecure-requests"] : []),
  ].join("; ");
}

/** 128 random bits, base64: a fresh nonce for every page response. */
function newNonce(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...bytes));
}

/** Pass the page on with its CSP: Next reads the nonce from the request's CSP header and stamps its scripts. */
function withCsp(request: NextRequest): NextResponse {
  const nonce = newNonce();
  const csp = contentSecurityPolicy(nonce, { dev: process.env.NODE_ENV === "development", https: request.nextUrl.protocol === "https:" });
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", csp);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  return response;
}

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // API calls are proxied to the API server, which does its own authentication and headers (D-32).
  // Not guarded here: the login call itself (POST /api/v1/auth/google) has no cookie yet.
  if (pathname.startsWith("/api/")) {
    return NextResponse.next();
  }

  // Allow public paths
  if (PUBLIC_FILES.has(pathname) || PUBLIC_PATHS.has(pathname.replace(/\/+$/, "") || "/")) {
    return withCsp(request);
  }

  // Check for access_token cookie
  const token = request.cookies.get("access_token")?.value;
  if (!token) {
    const loginUrl = new URL("/login", request.url);
    return NextResponse.redirect(loginUrl);
  }

  return withCsp(request);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
