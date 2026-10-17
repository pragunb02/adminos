"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { errorMessage } from "@/lib/query";
import { enrolPasskey, newRecoveryCodes, passkeyStatus } from "@/lib/passkeys";
import { passkeyErrorMessage, passkeysSupported } from "@/lib/webauthn";
import RecoveryCodes from "./RecoveryCodes";

const KEY = ["settings", "passkeys"];
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString("en-IN", { dateStyle: "medium" }) : "never");

/** Settings → Security → Passkeys (AOS-617): the passkeys that unlock AdminOS after Google, and the recovery codes. */
export default function PasskeyManager() {
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: KEY, queryFn: passkeyStatus });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);

  const run = async (work: () => Promise<void>, describe: (e: unknown) => string) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(false);
      await queryClient.invalidateQueries({ queryKey: KEY });
    }
  };

  const add = () => run(async () => {
    const recovery = await enrolPasskey();
    if (recovery.length > 0) setCodes(recovery);
  }, passkeyErrorMessage);

  const regenerate = () => {
    if (!window.confirm("Make new recovery codes? The old ones stop working.")) return;
    return run(async () => setCodes(await newRecoveryCodes()), (e) => errorMessage(e, "Couldn't make new codes"));
  };

  const keys = status.data?.passkeys ?? [];
  return (
    <section aria-labelledby="passkeys-heading" className="rounded-lg border border-gray-200 bg-white p-4">
      <h3 id="passkeys-heading" className="mb-1 font-semibold text-gray-900">Passkeys</h3>
      <p className="mb-3 text-sm text-gray-600">After Google, AdminOS asks for one of these. Add a second one (another device or a security key) so losing one doesn&apos;t lock you out.</p>
      {status.isPending && <p className="text-sm text-gray-600">Loading passkeys…</p>}
      {status.error && <p role="alert" className="text-sm text-red-700">{errorMessage(status.error)}</p>}
      {status.data && (
        <>
          <ul aria-label="Passkeys" className="divide-y divide-gray-100">
            {keys.map((k) => (
              <li key={k.id} className="py-2 text-sm">
                <p className="font-medium text-gray-900">{k.name}</p>
                <p className="text-xs text-gray-600">Added {when(k.createdAt)} · last used {when(k.lastUsedAt)}</p>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-gray-600">{status.data.recoveryCodesLeft} recovery codes left.</p>
        </>
      )}
      {codes && <div className="mt-3"><RecoveryCodes codes={codes} onDone={() => setCodes(null)} /></div>}
      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" disabled={busy || !passkeysSupported()} onClick={add}
          className="min-h-11 rounded-md bg-blue-700 px-4 text-sm font-semibold text-white disabled:opacity-60">Add a passkey</button>
        <button type="button" disabled={busy} onClick={regenerate}
          className="min-h-11 rounded-md border border-gray-300 px-4 text-sm disabled:opacity-60">New recovery codes</button>
      </div>
      {error && <p role="alert" className="mt-3 text-sm text-red-700">{error}</p>}
    </section>
  );
}
