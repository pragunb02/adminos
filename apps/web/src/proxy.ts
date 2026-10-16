import { NextRequest, NextResponse } from "next/server";

const PUBLIC_PATHS = ["/login", "/auth/callback", "/health"];

/**
 * Static files a browser fetches without the session cookie (AOS-510): the manifest is requested
 * without credentials, and the service worker re-checks itself even after a session expires.
 * They contain no data.
 */
const PUBLIC_FILES = new Set(["/manifest.webmanifest", "/sw.js", "/icon-192.png", "/icon-512.png", "/apple-icon.png"]);

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // API calls are proxied to the API server, which does its own authentication (D-32).
  // Not guarded here: the login call itself (POST /api/v1/auth/google) has no cookie yet.
  if (pathname.startsWith("/api/")) {
    return NextResponse.next();
  }

  // Allow public paths
  if (PUBLIC_FILES.has(pathname) || PUBLIC_PATHS.some((p) => pathname.startsWith(p))) {
    return NextResponse.next();
  }

  // Check for access_token cookie
  const token = request.cookies.get("access_token");
  if (!token) {
    const loginUrl = new URL("/login", request.url);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
