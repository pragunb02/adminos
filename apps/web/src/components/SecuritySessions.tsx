"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteData, errorMessage, getData, postData } from "@/lib/query";
import type { Session } from "@/lib/types";

const KEY = ["settings", "sessions"];

/** Where the browser goes once this device is signed out: a full reload drops every cached answer. */
// eslint-disable-next-line @next/next/no-location-assign-relative-destination -- a full reload drops all cached data when the session ends
export const signedOut = { go: () => window.location.assign("/login") };

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" }) : "never");

/**
 * Settings → Security (AOS-620): every signed-in session with its device and last activity; sign one out, every other
 * one, or all of them. Signing out ends a session's access at once (its tokens name it).
 */
export default function SecuritySessions() {
  const queryClient = useQueryClient();
  const sessions = useQuery({ queryKey: KEY, queryFn: () => getData<Session[]>("/api/v1/auth/sessions") });
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const act = async (work: () => Promise<boolean>, done: string) => {
    setBusy(true);
    setError(null);
    setStatus(null);
    try {
      const thisDeviceGone = await work();
      if (thisDeviceGone) return signedOut.go();
      setStatus(done);
    } catch (e) {
      setError(errorMessage(e, "Couldn't sign out. Try again."));
    } finally {
      setBusy(false);
      await queryClient.invalidateQueries({ queryKey: KEY });
    }
  };

  const signOut = (s: Session) =>
    act(async () => {
      await deleteData(`/api/v1/auth/sessions/${encodeURIComponent(s.id)}`);
      return s.current === true;
    }, `${s.device ?? "That device"} is signed out.`);

  const everywhere = (keepThis: boolean) => {
    const question = keepThis
      ? "Sign out every other device? They will need to sign in again."
      : "Sign out everywhere, this device included?";
    if (!window.confirm(question)) return;
    return act(async () => {
      const r = await postData<{ revoked: number; signedOut: boolean }>(`/api/v1/auth/sessions/revoke-all${keepThis ? "?keepCurrent=true" : ""}`, {});
      return r.signedOut;
    }, "Every other device is signed out.");
  };

  const list = sessions.data ?? [];
  const others = list.filter((s) => !s.current).length;

  return (
    <section aria-labelledby="sessions-heading" className="rounded-lg border border-gray-200 bg-white p-4">
      <h3 id="sessions-heading" className="mb-1 font-semibold text-gray-900">Where you&apos;re signed in</h3>
      <p className="mb-3 text-sm text-gray-600">
        You get an alert for every new sign-in and every refused one. If you don&apos;t recognise a device, sign it out, or sign out everywhere.
      </p>
      {sessions.isPending && <p className="text-sm text-gray-600">Loading sessions…</p>}
      {sessions.error && <p role="alert" className="text-sm text-red-700">{errorMessage(sessions.error)}</p>}
      {sessions.data && list.length === 0 && <p className="text-sm text-gray-600">No active sessions.</p>}
      {list.length > 0 && (
        <ul className="divide-y divide-gray-100" aria-label="Sessions">
          {list.map((s) => (
            <li key={s.id} aria-label={s.device ?? "Unknown device"} className="flex items-center justify-between gap-3 py-2">
              <div className="min-w-0">
                <p className="text-sm font-medium text-gray-900">
                  {s.device ?? "Unknown device"}
                  {s.current && <span className="ml-2 rounded-full bg-green-100 px-2 py-0.5 text-xs text-green-800">This device</span>}
                </p>
                <p className="text-xs text-gray-600">
                  Last active {when(s.lastActiveAt ?? s.createdAt)} · signed in {when(s.createdAt)}
                  {s.ipAddress && ` · ${s.ipAddress}`}
                </p>
              </div>
              <button type="button" disabled={busy} onClick={() => signOut(s)}
                className="min-h-11 shrink-0 rounded-md border border-red-200 px-3 text-sm text-red-700 hover:bg-red-50 disabled:opacity-60">
                Sign out
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-4 flex flex-wrap gap-2">
        {others > 0 && (
          <button type="button" disabled={busy} onClick={() => everywhere(true)}
            className="min-h-11 rounded-md border border-gray-300 px-4 text-sm disabled:opacity-60">
            Sign out everywhere else
          </button>
        )}
        <button type="button" disabled={busy} onClick={() => everywhere(false)}
          className="min-h-11 rounded-md bg-red-700 px-4 text-sm font-semibold text-white disabled:opacity-60">
          Sign out everywhere
        </button>
      </div>
      {status && <p role="status" className="mt-3 text-sm text-gray-700">{status}</p>}
      {error && <p role="alert" className="mt-3 text-sm text-red-700">{error}</p>}
    </section>
  );
}
