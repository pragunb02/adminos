"use client";

import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import ConnectGmailButton from "@/components/ConnectGmailButton";
import DeviceTokens from "@/components/DeviceTokens";
import PushDevice from "@/components/PushDevice";
import AiFeatures from "@/components/AiFeatures";
import { deleteData, errorMessage, getData, patchData, postData } from "@/lib/query";
import type { UserConnection, NotificationPreferences } from "@/lib/types";
import Link from "next/link";
import AppShell from "@/components/AppShell";
import StatusBadge from "@/components/StatusBadge";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];

export default function SettingsPage() {
  const queryClient = useQueryClient();
  const PREFS_KEY = ["settings", "notification-preferences"];
  const connectionsQuery = useQuery({ queryKey: ["settings", "connections"], queryFn: () => getData<UserConnection[]>("/api/v1/connections") });
  const prefsQuery = useQuery({ queryKey: PREFS_KEY, queryFn: () => getData<NotificationPreferences>("/api/v1/users/me/notification-preferences") });
  const connections = connectionsQuery.data ?? [];
  const prefs = prefsQuery.data ?? null;
  const loading = connectionsQuery.isPending && prefsQuery.isPending;
  const error = connectionsQuery.error && prefsQuery.error ? "Failed to load settings" : null;
  const fetchData = () => queryClient.invalidateQueries({ queryKey: ["settings"] });
  const [saving, setSaving] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const prefTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingPrefsRef = useRef<Record<string, unknown>>({});

  // Optimistic: the toggle changes at once; the save is debounced (500ms) and a failure reloads the real values.
  const updatePref = (key: string, value: unknown) => {
    if (!prefs) return;
    queryClient.setQueryData<NotificationPreferences>(PREFS_KEY, { ...prefs, [key]: value });
    pendingPrefsRef.current = { ...pendingPrefsRef.current, [key]: value };
    if (prefTimerRef.current) clearTimeout(prefTimerRef.current);
    prefTimerRef.current = setTimeout(async () => {
      const batch = { ...pendingPrefsRef.current };
      pendingPrefsRef.current = {};
      setSaving(true);
      try {
        await patchData("/api/v1/users/me/notification-preferences", batch);
      } catch (e) {
        setActionError(errorMessage(e, "Couldn't save your notification settings"));
        queryClient.invalidateQueries({ queryKey: PREFS_KEY });
      } finally {
        setSaving(false);
      }
    }, 500);
  };

  const run = async (work: () => Promise<unknown>, refresh = true) => {
    setActionError(null);
    try {
      await work();
      if (refresh) await fetchData();
    } catch (e) {
      setActionError(errorMessage(e));
    }
  };
  const handleDisconnect = (id: string) => run(() => deleteData(`/api/v1/connections/${id}`));
  const handleSync = (id: string) => run(() => postData(`/api/v1/connections/${id}/sync`, {}), false);
  const handleReconnect = (sourceType: string) => run(() => postData("/api/v1/connections", { sourceType }));

  return (
    <AppShell>
      <div className="space-y-6">
        <h2 className="text-xl font-bold">Settings</h2>

        {loading && <LoadingSpinner message="Loading settings..." />}
        {error && <ErrorMessage message={error} onRetry={fetchData} />}
        {actionError && <ErrorMessage message={actionError} />}

        {!loading && !error && (
          <>
            {/* Data Sources (Connect Gmail: AOS-406) */}
            <section className="bg-white border border-gray-200 rounded-lg p-4">
              <h3 className="font-semibold text-gray-900 mb-3">Data Sources</h3>
              {connections.length === 0 ? (
                <p className="text-sm text-gray-500">No data sources connected yet.</p>
              ) : (
                <ul className="space-y-3">
                  {connections.map((conn) => (
                    <li key={conn.id} aria-label={`${conn.sourceType} connection`} className="flex items-center justify-between gap-3 py-2 border-b border-gray-100 last:border-0">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="text-sm font-medium text-gray-900 capitalize">{conn.sourceType}</p>
                          <StatusBadge status={conn.status} />
                        </div>
                        {conn.gmailAddress && <p className="text-xs text-gray-500 mt-0.5">{conn.gmailAddress}</p>}
                        <div className="flex flex-wrap gap-3 mt-1 text-xs text-gray-500">
                          {conn.lastSyncedAt && <span>Last sync: {new Date(conn.lastSyncedAt).toLocaleDateString("en-IN")}</span>}
                          <span>{conn.totalSynced} items synced</span>
                          {pushNote(conn) && <span>{pushNote(conn)}</span>}
                        </div>
                        {conn.lastError && <p className="text-xs text-red-700 mt-1">Last error: {conn.lastError}</p>}
                      </div>
                      <div className="flex flex-wrap items-start justify-end gap-2">
                        {conn.status === "connected" && (
                          <button
                            onClick={() => handleSync(conn.id)}
                            className="text-xs px-2.5 py-1.5 rounded-md border border-gray-200 hover:bg-gray-50"
                          >
                            Sync
                          </button>
                        )}
                        {conn.sourceType === "gmail" && (conn.status === "error" || conn.status === "disconnected") && (
                          <ConnectGmailButton label="Reconnect" />
                        )}
                        {conn.sourceType !== "gmail" && conn.status === "error" && (
                          <button
                            onClick={() => handleReconnect(conn.sourceType)}
                            className="text-xs px-2.5 py-1.5 rounded-md border border-blue-200 text-blue-600 hover:bg-blue-50"
                          >
                            Reconnect
                          </button>
                        )}
                        {conn.status !== "disconnected" && (
                          <button
                            onClick={() => handleDisconnect(conn.id)}
                            className="text-xs px-2.5 py-1.5 rounded-md border border-red-200 text-red-600 hover:bg-red-50"
                          >
                            Disconnect
                          </button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
              {!connections.some((c) => c.sourceType === "gmail") && (
                <div className="mt-3 flex items-start justify-between gap-3 border-t border-gray-100 pt-3">
                  <p className="text-xs text-gray-500">Gmail: import bank alerts and receipts (read-only access).</p>
                  <ConnectGmailButton />
                </div>
              )}
            </section>

            {/* Notification Preferences */}
            {prefs && (
              <section className="bg-white border border-gray-200 rounded-lg p-4">
                <h3 className="font-semibold text-gray-900 mb-3">
                  Notification Preferences
                  {saving && <span className="text-xs text-gray-500 ml-2">Saving...</span>}
                </h3>
                <div className="space-y-4">
                  <Toggle label="Weekly briefing" checked={prefs.weeklyBriefing} onChange={(v) => updatePref("weeklyBriefing", v)} />
                  {prefs.weeklyBriefing && (
                    <div className="ml-6 flex gap-3 items-center">
                      <select
                        aria-label="Briefing day"
                        value={prefs.briefingDay}
                        onChange={(e) => updatePref("briefingDay", e.target.value)}
                        className="text-sm border border-gray-300 rounded-md px-2 py-1 bg-white"
                      >
                        {DAYS.map((d) => (
                          <option key={d} value={d}>{d.charAt(0).toUpperCase() + d.slice(1)}</option>
                        ))}
                      </select>
                      <input
                        type="time"
                        aria-label="Briefing time"
                        value={prefs.briefingTime}
                        onChange={(e) => updatePref("briefingTime", e.target.value)}
                        className="text-sm border border-gray-300 rounded-md px-2 py-1"
                      />
                    </div>
                  )}
                  <Toggle label="Bill reminders" checked={prefs.billReminders} onChange={(v) => updatePref("billReminders", v)} />
                  <Toggle label="Anomaly alerts" checked={prefs.anomalyAlerts} onChange={(v) => updatePref("anomalyAlerts", v)} />
                  <Toggle label="Subscription flags" checked={prefs.subscriptionFlags} onChange={(v) => updatePref("subscriptionFlags", v)} />

                  <div className="border-t border-gray-100 pt-4">
                    <p className="text-xs font-semibold text-gray-500 uppercase mb-3">Channels</p>
                    <Toggle label="Email notifications" checked={prefs.emailNotifications} onChange={(v) => updatePref("emailNotifications", v)} />
                    <Toggle label="Push notifications" checked={prefs.pushNotifications} onChange={(v) => updatePref("pushNotifications", v)} />
                  </div>

                  <div className="border-t border-gray-100 pt-4">
                    <p className="text-xs font-semibold text-gray-500 uppercase mb-3">Quiet hours</p>
                    <div className="flex items-center gap-2 text-sm">
                      <input
                        type="time"
                        aria-label="Quiet hours start"
                        value={prefs.quietHoursStart}
                        onChange={(e) => updatePref("quietHoursStart", e.target.value)}
                        className="border border-gray-300 rounded-md px-2 py-1"
                      />
                      <span className="text-gray-500">to</span>
                      <input
                        type="time"
                        aria-label="Quiet hours end"
                        value={prefs.quietHoursEnd}
                        onChange={(e) => updatePref("quietHoursEnd", e.target.value)}
                        className="border border-gray-300 rounded-md px-2 py-1"
                      />
                    </div>
                  </div>
                </div>
              </section>
            )}

            {/* Push on this device (AOS-510) */}
            <PushDevice />

            {/* AI features: per-feature consent and notice (AOS-704) */}
            <AiFeatures />

            {/* Connected devices (AOS-203) */}
            <DeviceTokens />

            {/* Security: sessions and sign-out live on their own page (AOS-620) */}
            <section className="bg-white border border-gray-200 rounded-lg p-4">
              <h3 className="font-semibold text-gray-900 mb-1">Security</h3>
              <p className="text-sm text-gray-600 mb-2">Where you&apos;re signed in, and signing out everywhere.</p>
              <Link href="/settings/security" className="text-sm text-accent underline">Open security settings</Link>
            </section>
          </>
        )}
      </div>
    </AppShell>
  );
}

/** Gmail push status (AOS-405): instant while the watch is on, else the daily check. */
function pushNote(conn: UserConnection, now = Date.now()): string | null {
  if (conn.sourceType !== "gmail" || conn.status !== "connected") return null;
  if (conn.watchExpiresAt && Date.parse(conn.watchExpiresAt) > now) {
    return `Instant updates on until ${new Date(conn.watchExpiresAt).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}`;
  }
  return "Checked once a day";
}

function Toggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex items-center justify-between cursor-pointer py-1">
      <span className="text-sm text-gray-700">{label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
          checked ? "bg-blue-600" : "bg-gray-200"
        }`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
            checked ? "translate-x-6" : "translate-x-1"
          }`}
        />
      </button>
    </label>
  );
}
