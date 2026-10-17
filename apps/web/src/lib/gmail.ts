/**
 * Connect Gmail (AOS-406, D-64): the API builds Google's consent URL with a single-use `state`
 * (CSRF) and a PKCE challenge; Google sends the user back to the callback page with a code and
 * that state; the page posts both to the API, which checks the state before using the code.
 * Components use these helpers and never build the URLs or read error codes themselves.
 */

import { ApiError, getData, postData } from "./query";
import type { UserConnection } from "./types";

export const GMAIL_CALLBACK_PATH = "/connections/gmail/callback";

/** Where Google sends the user back; must be registered on the Google OAuth client (AOS-111). */
export const gmailRedirectUri = (origin: string = window.location.origin) => `${origin}${GMAIL_CALLBACK_PATH}`;

/** Leaving the app for Google; a seam so tests can see where it would go. */
export const browser = {
  go(url: string) {
    window.location.assign(url);
  },
};

/** Asks the API for Google's consent URL (with a fresh state) and goes there. */
export async function startGmailConnect(): Promise<void> {
  const { url } = await getData<{ url: string }>(
    `/api/v1/connections/gmail/authorize-url?redirectUri=${encodeURIComponent(gmailRedirectUri())}`,
  );
  browser.go(url);
}

/** Redeems Google's answer: the code and the state it came back with. */
export const finishGmailConnect = (code: string, state: string) =>
  postData<UserConnection>("/api/v1/connections", { sourceType: "gmail", code, state, redirectUri: gmailRedirectUri() });

/** Why Gmail isn't connected, in words, and whether starting again can help. */
export interface GmailProblem {
  title: string;
  detail: string;
  canRetry: boolean;
}

const START_AGAIN = "Start again from Settings → Data sources.";

/** Google's own `error` on the callback (RFC 6749 §4.1.2.1). */
export function googleProblem(error: string): GmailProblem {
  if (error === "access_denied") {
    return { title: "You didn't allow access", detail: "Gmail wasn't connected because the Google consent was cancelled. You can connect it any time.", canRetry: true };
  }
  return { title: "Google couldn't finish the sign-in", detail: `Google answered "${error}". ${START_AGAIN}`, canRetry: true };
}

/** The callback was opened without Google's answer (bookmarked, or the address was cut). */
export const incompleteProblem: GmailProblem = {
  title: "This page needs Google's answer",
  detail: "Open it by choosing Connect Gmail in Settings; Google brings you back here.",
  canRetry: true,
};

const BY_CODE: Record<string, GmailProblem> = {
  CONN_010: { title: "This sign-in didn't start here", detail: `Google's answer doesn't match a Connect Gmail you started while signed in, so it was ignored. ${START_AGAIN}`, canRetry: true },
  CONN_011: { title: "The sign-in took too long", detail: `Google's answer came more than 10 minutes after you started. ${START_AGAIN}`, canRetry: true },
  CONN_012: { title: "This sign-in was already used", detail: "Each Google sign-in works once. If Gmail isn't connected yet, start again.", canRetry: true },
  CONN_007: { title: "Gmail access wasn't allowed", detail: "AdminOS needs read-only access to Gmail (\"View your email messages and settings\") to import bank emails. Start again and leave that box ticked.", canRetry: true },
  CONN_008: { title: "Google didn't give lasting access", detail: "Without it AdminOS could read Gmail for an hour only. Start again; if it keeps happening, remove AdminOS at myaccount.google.com/permissions first.", canRetry: true },
  CONN_009: { title: "Google sign-in isn't set up", detail: "The server has no Google OAuth client yet (GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET), so Gmail can't be connected.", canRetry: false },
  CONN_006: { title: "Google sign-in isn't set up", detail: "The server has no Google OAuth client yet (GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET), so Gmail can't be connected.", canRetry: false },
  CONN_002: { title: "Google didn't accept the sign-in", detail: `The code from Google was refused; it may have expired. ${START_AGAIN}`, canRetry: true },
  NETWORK: { title: "AdminOS couldn't be reached", detail: "Check your connection, then start again.", canRetry: true },
};

/** Any failure of the authorize-url or connect call. */
export function connectProblem(e: unknown): GmailProblem {
  if (e instanceof ApiError && BY_CODE[e.code]) return BY_CODE[e.code];
  return { title: "Gmail isn't connected", detail: e instanceof Error ? e.message : "Something went wrong.", canRetry: true };
}
