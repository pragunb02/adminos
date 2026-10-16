"use client";

import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { errorMessage, getData } from "@/lib/query";
import { currentSubscription, permission, pushSupport, sendTest, turnOff, turnOn, type PushStatus } from "@/lib/push";

const KEY = ["settings", "push"];

/** Where this device stands, decided once the browser has been asked. */
type Local = { kind: "checking" } | { kind: "ready"; subscribed: boolean };

/**
 * Settings → Notifications on this device (AOS-510). Explains each case in words: the server has
 * no push keys, the browser can't do push, an iPhone needs "Add to Home Screen" first, notifications
 * are blocked, or push is on/off here.
 */
export default function PushDevice() {
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: KEY, queryFn: () => getData<PushStatus>("/api/v1/push") });
  const [support] = useState(() => (typeof window === "undefined" ? "unsupported" : pushSupport()));
  const [local, setLocal] = useState<Local>({ kind: "checking" });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    if (support !== "supported") return;
    let live = true;
    currentSubscription()
      .then((s) => live && setLocal({ kind: "ready", subscribed: s !== null }))
      .catch(() => live && setLocal({ kind: "ready", subscribed: false }));
    return () => {
      live = false;
    };
  }, [support]);

  const act = async (work: () => Promise<string | null>) => {
    setBusy(true);
    setFailure(null);
    setMessage(null);
    try {
      setMessage(await work());
    } catch (e) {
      setFailure(errorMessage(e, "Something went wrong with notifications"));
    } finally {
      setBusy(false);
      queryClient.invalidateQueries({ queryKey: KEY });
    }
  };

  const others = status.data?.devices.length ?? 0;

  return (
    <section aria-labelledby="push-heading" className="rounded-lg border border-gray-200 bg-white p-4">
      <h3 id="push-heading" className="mb-1 font-semibold text-gray-900">Notifications on this device</h3>
      <p className="mb-3 text-sm text-gray-600">Bill reminders and alerts as phone or desktop notifications.</p>
      {(() => {
        if (status.isPending || (support === "supported" && local.kind === "checking")) return <p className="text-sm text-gray-600">Checking…</p>;
        if (status.error) return <p role="alert" className="text-sm text-red-700">{errorMessage(status.error)}</p>;
        if (!status.data.enabled || !status.data.publicKey)
          return <p className="text-sm text-gray-700">Push notifications aren&apos;t set up on the server yet (VAPID keys in <code>.env</code>).</p>;
        if (support === "needs-install")
          return (
            <p className="text-sm text-gray-700">
              On iPhone, notifications work from the Home Screen app: tap <strong>Share</strong> → <strong>Add to Home Screen</strong>, then open AdminOS from your Home Screen and come back here.
            </p>
          );
        if (support === "unsupported") return <p className="text-sm text-gray-700">This browser can&apos;t receive push notifications.</p>;
        if (permission() === "denied")
          return <p className="text-sm text-gray-700">Notifications are blocked for AdminOS. Allow them in your browser or iPhone settings, then reload this page.</p>;
        const publicKey = status.data.publicKey;
        const subscribed = local.kind === "ready" && local.subscribed;
        return (
          <div className="space-y-3">
            <p className="text-sm text-gray-700">
              {subscribed ? "On for this device." : "Off for this device."}
              {others > 0 && ` Push is on for ${others} device${others === 1 ? "" : "s"} in total.`}
            </p>
            <div className="flex flex-wrap gap-2">
              {subscribed ? (
                <>
                  <button type="button" disabled={busy} className="min-h-11 rounded-md bg-blue-700 px-4 text-sm font-semibold text-white disabled:opacity-60"
                    onClick={() => act(async () => {
                      const r = await sendTest();
                      return r.delivered ? "Test sent — it should appear in a few seconds." : `The test didn't go through: ${r.detail}`;
                    })}>
                    Send a test
                  </button>
                  <button type="button" disabled={busy} className="min-h-11 rounded-md border border-gray-300 px-4 text-sm disabled:opacity-60"
                    onClick={() => act(async () => { await turnOff(); setLocal({ kind: "ready", subscribed: false }); return "Notifications are off for this device."; })}>
                    Turn off
                  </button>
                </>
              ) : (
                <button type="button" disabled={busy} className="min-h-11 rounded-md bg-blue-700 px-4 text-sm font-semibold text-white disabled:opacity-60"
                  onClick={() => act(async () => { await turnOn(publicKey); setLocal({ kind: "ready", subscribed: true }); return "Notifications are on for this device."; })}>
                  Turn on notifications
                </button>
              )}
            </div>
          </div>
        );
      })()}
      {message && <p role="status" className="mt-3 text-sm text-gray-700">{message}</p>}
      {failure && <p role="alert" className="mt-3 text-sm text-red-700">{failure}</p>}
    </section>
  );
}
