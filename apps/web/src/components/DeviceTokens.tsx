"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";

interface DeviceToken {
  id: string;
  name: string;
  scopes: string[];
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
  token?: string | null;
}

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "never");

/**
 * Settings → Connected devices (AOS-203, D-36). Issues scoped device tokens (e.g. for the Mac SMS
 * reader). The secret is shown once, right after creation, and never again.
 */
export default function DeviceTokens() {
  const [tokens, setTokens] = useState<DeviceToken[]>([]);
  const [name, setName] = useState("My Mac – SMS reader");
  const [fresh, setFresh] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const res = await api.get<DeviceToken[]>("/api/v1/device-tokens");
    if (res.success && res.data) setTokens(res.data);
    else setError(res.error?.message ?? "Couldn't load devices");
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const create = async () => {
    setError(null);
    const res = await api.post<DeviceToken>("/api/v1/device-tokens", { name });
    if (res.success && res.data?.token) {
      setFresh(res.data.token);
      setCopied(false);
      load();
    } else setError(res.error?.message ?? "Couldn't create the token");
  };

  const revoke = async (id: string) => {
    if (!window.confirm("Revoke this device? It will stop sending SMS immediately.")) return;
    await api.delete(`/api/v1/device-tokens/${id}`);
    load();
  };

  const copy = async () => {
    if (!fresh) return;
    await navigator.clipboard.writeText(fresh);
    setCopied(true);
  };

  const active = tokens.filter((t) => !t.revokedAt);

  return (
    <section className="bg-white border border-gray-200 rounded-lg p-4" aria-labelledby="devices-heading">
      <h3 id="devices-heading" className="font-semibold mb-1">Connected devices</h3>
      <p className="text-sm text-gray-500 mb-3">
        Devices that send bank SMS to AdminOS (for example the Mac SMS reader). A device token can only submit SMS; it
        can&apos;t read your data.
      </p>

      {fresh && (
        <div role="status" className="mb-4 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm">
          <p className="font-medium text-amber-900">Copy this token now. It won&apos;t be shown again.</p>
          <code className="mt-2 block break-all rounded-sm bg-white p-2 text-xs text-gray-800">{fresh}</code>
          <div className="mt-2 flex gap-2">
            <button type="button" onClick={copy} className="rounded-md bg-amber-600 px-3 py-1.5 text-white hover:bg-amber-700">
              {copied ? "Copied ✓" : "Copy token"}
            </button>
            <button type="button" onClick={() => setFresh(null)} className="rounded-md px-3 py-1.5 text-amber-900 hover:bg-amber-100">
              Done
            </button>
          </div>
        </div>
      )}

      <div className="flex gap-2 mb-3">
        <input
          aria-label="Device name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={100}
          className="flex-1 rounded-md border border-gray-300 px-3 py-1.5 text-sm"
        />
        <button
          type="button"
          onClick={create}
          disabled={!name.trim()}
          className="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
        >
          Connect device
        </button>
      </div>

      {error && <p role="alert" className="text-sm text-red-600 mb-2">{error}</p>}

      {active.length === 0 ? (
        <p className="text-sm text-gray-400">No devices connected.</p>
      ) : (
        <ul className="divide-y divide-gray-100">
          {active.map((t) => (
            <li key={t.id} className="flex items-center justify-between py-2 text-sm">
              <div>
                <p className="font-medium">{t.name}</p>
                <p className="text-gray-500">
                  Last used {fmt(t.lastUsedAt)} · expires {fmt(t.expiresAt)}
                </p>
              </div>
              <button type="button" onClick={() => revoke(t.id)} className="text-red-600 hover:underline">
                Revoke
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
