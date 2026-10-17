import { createHmac, randomUUID } from "node:crypto";
import type { BrowserContext } from "@playwright/test";
import { E2E_JWT_SECRET } from "../../playwright.config";

const b64url = (v: string | Buffer) => Buffer.from(v).toString("base64url");

/**
 * A real access token for the test API (AOS-311 full-stack mode), signed with the throwaway secret the
 * test API was started with — the same claims JwtService issues. No production bypass is involved.
 */
export function signAccessToken(
  userId = randomUUID(),
  opts: { tz?: string; secret?: string; expiresInSec?: number; typ?: string; iss?: string; alg?: "HS256" | "none" } = {},
) {
  const now = Math.floor(Date.now() / 1000);
  const alg = opts.alg ?? "HS256";
  const header = b64url(JSON.stringify({ alg }));
  const claims = b64url(JSON.stringify({
    sub: userId, typ: opts.typ ?? "access", email: `${userId}@e2e.test`, role: "owner", tz: opts.tz ?? "Asia/Kolkata",
    iss: opts.iss ?? "adminos", iat: now, exp: now + (opts.expiresInSec ?? 600),
  }));
  // An attacker's "alg: none" token carries no signature at all (AOS-619 attack specs).
  const sig = alg === "none" ? "" : createHmac("sha256", opts.secret ?? E2E_JWT_SECRET).update(`${header}.${claims}`).digest("base64url");
  return { userId, token: `${header}.${claims}.${sig}` };
}

/** [token] with its payload replaced by [claims] but its original signature kept: a tampered token. */
export function tamper(token: string, claims: Record<string, unknown>) {
  const [header, , sig] = token.split(".");
  return `${header}.${b64url(JSON.stringify(claims))}.${sig}`;
}

export async function signIn(context: BrowserContext, baseURL: string, token: string) {
  await context.addCookies([{ name: "access_token", value: token, url: baseURL, httpOnly: true, sameSite: "Lax" }]);
}
