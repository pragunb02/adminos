"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { errorMessage } from "@/lib/query";
import { enrolPasskey, passkeyStatus, redeemRecoveryCode, verifyPasskey, type PasskeyStep } from "@/lib/passkeys";
import { passkeyErrorMessage, passkeysSupported } from "@/lib/webauthn";
import RecoveryCodes from "@/components/RecoveryCodes";

/**
 * The second step after Google (AOS-617): set up the first passkey, or confirm with one. Until then the session can
 * reach nothing else. A recovery code works instead of a lost passkey.
 */
export default function PasskeyPage() {
  const router = useRouter();
  const [step, setStep] = useState<PasskeyStep | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [recovering, setRecovering] = useState(false);
  const [code, setCode] = useState("");

  useEffect(() => {
    let live = true;
    passkeyStatus()
      .then((s) => {
        if (!live) return;
        if (s.step === "done") router.replace("/");
        else setStep(s.step);
      })
      .catch((e) => live && setLoadError(errorMessage(e, "Couldn't check your sign-in")));
    return () => { live = false; };
  }, [router]);

  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(passkeyErrorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const enrol = () => run(async () => {
    const recovery = await enrolPasskey();
    if (recovery.length > 0) setCodes(recovery);
    else router.replace("/");
  });
  const verify = () => run(async () => {
    await verifyPasskey();
    router.replace("/");
  });
  const recover = (e: React.FormEvent) => {
    e.preventDefault();
    return run(async () => {
      await redeemRecoveryCode(code);
      router.replace("/");
    });
  };

  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-white p-8">
      <div className="w-full max-w-sm space-y-4 text-center">
        <h1 className="text-2xl font-bold text-gray-900">{step === "enrol" ? "Set up a passkey" : "Confirm it's you"}</h1>

        {loadError && <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-700">{loadError}</p>}
        {step === null && !loadError && <p className="text-sm text-gray-600">Checking your sign-in…</p>}

        {codes && <RecoveryCodes codes={codes} onDone={() => router.replace("/")} />}

        {!codes && step === "enrol" && (
          <>
            <p className="text-sm text-gray-600">
              AdminOS needs a passkey as well as Google, so a stolen Google sign-in alone can&apos;t open it. Use Face ID, Touch ID, Windows Hello or a security key.
            </p>
            <button type="button" onClick={enrol} disabled={busy || !passkeysSupported()}
              className="min-h-11 w-full rounded-lg bg-blue-700 px-4 text-sm font-semibold text-white disabled:opacity-60">
              {busy ? "Waiting for your passkey…" : "Set up a passkey"}
            </button>
          </>
        )}

        {!codes && step === "verify" && (
          <>
            <p className="text-sm text-gray-600">Signed in with Google. Now use your passkey.</p>
            <button type="button" onClick={verify} disabled={busy || !passkeysSupported()}
              className="min-h-11 w-full rounded-lg bg-blue-700 px-4 text-sm font-semibold text-white disabled:opacity-60">
              {busy ? "Waiting for your passkey…" : "Use your passkey"}
            </button>
            {!recovering ? (
              <button type="button" onClick={() => setRecovering(true)} className="min-h-11 text-sm text-blue-700 underline">
                Lost your passkey? Use a recovery code
              </button>
            ) : (
              <form onSubmit={recover} className="space-y-2 text-left">
                <label htmlFor="recovery-code" className="block text-sm font-medium text-gray-700">Recovery code</label>
                <input id="recovery-code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code"
                  maxLength={32} spellCheck={false} className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm" />
                <button type="submit" disabled={busy || code.trim().length < 16}
                  className="min-h-11 w-full rounded-lg border border-gray-300 px-4 text-sm disabled:opacity-60">
                  Use recovery code
                </button>
              </form>
            )}
          </>
        )}

        {step !== null && !passkeysSupported() && !codes && (
          <p role="alert" className="rounded-md bg-amber-50 p-3 text-sm text-amber-800">This browser can&apos;t use passkeys. Open AdminOS in a current Safari, Chrome, Edge or Firefox.</p>
        )}
        {error && <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}

        <button type="button" onClick={() => api.logout()} className="min-h-11 text-sm text-gray-600 underline">Sign out</button>
      </div>
    </main>
  );
}
