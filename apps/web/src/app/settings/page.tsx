"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { api } from "@/lib/api";
import type { UserConnection, NotificationPreferences, Session } from "@/lib/types";
import AppShell from "@/components/AppShell";
import StatusBadge from "@/components/StatusBadge";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"];

export default function SettingsPage() {
  const [connections, setConnections] = useState<UserConnection[]>([]);
  const [prefs, setPrefs] = useState<NotificationPreferences | null>(null);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [connRes, prefsRes, sessRes] = await Promise.all([
        api.get<UserConnection[]>("/api/v1/connections"),
        api.get<NotificationPreferences>("/api/v1/users/me/notification-preferences"),
        api.get<Session[]>("/api/v1/auth/sessions"),
      ]);
      if (connRes.success && connRes.data) setConnections(connRes.data);
      if (prefsRes.success && prefsRes.data) setPrefs(prefsRes.data);
      if (sessRes.success && sessRes.data) setSessions(sessRes.data);
      if (!connRes.success && !prefsRes.success) setError("Failed to load settings");
    } catch {
      setError("Unable to connect to server");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const prefTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingPrefsRef = useRef<Record<string, unknown>>({});

  const updatePref = async (key: string, value: unknown) => {
    if (!prefs) return;
    const updated = { ...prefs, [key]: value };
    setPrefs(updated);

    // Accumulate pending changes
    pendingPrefsRef.current = { ...pendingPrefsRef.current, [key]: value };

    // Debounce the API call (500ms)
    if (prefTimerRef.current) clearTimeout(prefTimerRef.current);
    prefTimerRef.current = setTimeout(async () => {
      const batch = { ...pendingPrefsRef.current };
      pendingPrefsRef.current = {};
      setSaving(true);
      try {
        await api.patch("/api/v1/users/me/notification-preferences", batch);
      } catch {
        // revert on error
        setPrefs(prefs);
      } finally {
        setSaving(false);
      }
    }, 500);
  };

  const handleDisconnect = async (id: string) => {
    try {
      await api.delete(`/api/v1/connections/${id}`);
      await fetchData();
    } catch {
      // silent
    }
  };

  const handleSync = async (id: string) => {
    try {
      await api.post(`/api/v1/connections/${id}/sync`, {});
    } catch {
      // silent
    }
  };

  const handleReconnect = async (sourceType: string) => {
    try {
      await api.post("/api/v1/connections", { source_type: sourceType });
      await fetchData();
    } catch {
      // silent
    }
  };

  return (
    <AppShell>
      <div className="space-y-6">
        <h2 className="text-xl font-bold">Settings</h2>

        {loading && <LoadingSpinner message="Loading settings..." />}
        {error && <ErrorMessage message={error} onRetry={fetchData} />}

        {!loading && !error && (
          <>
            {/* Data Sources */}
            <section className="bg-white border border-gray-200 rounded-lg p-4">
              <h3 className="font-semibold text-gray-900 mb-3">Data Sources</h3>
              {connections.length === 0 ? (
                <p className="text-sm text-gray-500">No data sources connected yet.</p>
              ) : (
                <div className="space-y-3">
                  {connections.map((conn) => (
                    <div key={conn.id} className="flex items-center justify-between py-2 border-b border-gray-100 last:border-0">
                      <div>
                        <div className="flex items-center gap-2">
                          <p className="text-sm font-medium text-gray-900 capitalize">{conn.sourceType}</p>
                          <StatusBadge status={conn.status} />
                        </div>
                        <div className="flex gap-3 mt-1 text-xs text-gray-400">
                          {conn.lastSyncedAt && <span>Last sync: {new Date(conn.lastSyncedAt).toLocaleDateString("en-IN")}</span>}
                          <span>{conn.totalSynced} items synced</span>
                        </div>
                      </div>
                      <div className="flex gap-2">
                        <button
                          onClick={() => handleSync(conn.id)}
                          className="text-xs px-2.5 py-1.5 rounded-md border border-gray-200 hover:bg-gray-50"
                        >
                          Sync
                        </button>
                        {conn.status === "error" && (
                          <button
                            onClick={() => handleReconnect(conn.sourceType)}
                            className="text-xs px-2.5 py-1.5 rounded-md border border-blue-200 text-blue-600 hover:bg-blue-50"
                          >
                            Reconnect
                          </button>
                        )}
                        <button
                          onClick={() => handleDisconnect(conn.id)}
                          className="text-xs px-2.5 py-1.5 rounded-md border border-red-200 text-red-600 hover:bg-red-50"
                        >
                          Disconnect
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            {/* Notification Preferences */}
            {prefs && (
              <section className="bg-white border border-gray-200 rounded-lg p-4">
                <h3 className="font-semibold text-gray-900 mb-3">
                  Notification Preferences
                  {saving && <span className="text-xs text-gray-400 ml-2">Saving...</span>}
                </h3>
                <div className="space-y-4">
                  <Toggle label="Weekly briefing" checked={prefs.weeklyBriefing} onChange={(v) => updatePref("weeklyBriefing", v)} />
                  {prefs.weeklyBriefing && (
                    <div className="ml-6 flex gap-3 items-center">
                      <select
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
                        value={prefs.quietHoursStart}
                        onChange={(e) => updatePref("quietHoursStart", e.target.value)}
                        className="border border-gray-300 rounded-md px-2 py-1"
                      />
                      <span className="text-gray-400">to</span>
                      <input
                        type="time"
                        value={prefs.quietHoursEnd}
                        onChange={(e) => updatePref("quietHoursEnd", e.target.value)}
                        className="border border-gray-300 rounded-md px-2 py-1"
                      />
                    </div>
                  </div>
                </div>
              </section>
            )}

            {/* Sessions */}
            <section className="bg-white border border-gray-200 rounded-lg p-4">
              <h3 className="font-semibold text-gray-900 mb-3">Active Sessions</h3>
              {sessions.length === 0 ? (
                <p className="text-sm text-gray-500">No active sessions.</p>
              ) : (
                <div className="space-y-2">
                  {sessions.map((s) => (
                    <div key={s.id} className="flex items-center justify-between py-2 border-b border-gray-100 last:border-0">
                      <div>
                        <p className="text-sm text-gray-700">{s.userAgent || "Unknown device"}</p>
                        <p className="text-xs text-gray-400">
                          {s.ipAddress || "Unknown IP"}
                          {s.lastActiveAt && ` · Last active: ${new Date(s.lastActiveAt).toLocaleDateString("en-IN")}`}
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>
          </>
        )}
      </div>
    </AppShell>
  );
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
