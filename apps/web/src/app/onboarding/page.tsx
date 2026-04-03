"use client";

import { useState, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import type { SyncSession, Subscription } from "@/lib/types";

type Step = "education" | "gmail" | "sms" | "processing" | "magic";

export default function OnboardingPage() {
  const router = useRouter();
  const [step, setStep] = useState<Step>("education");
  const [syncId, setSyncId] = useState<string | null>(null);
  const [syncStatus, setSyncStatus] = useState<SyncSession | null>(null);
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [totalMonthlyCost, setTotalMonthlyCost] = useState(0);
  const [skippedGmail, setSkippedGmail] = useState(false);
  const [skippedSms, setSkippedSms] = useState(false);

  // Poll sync progress
  useEffect(() => {
    if (step !== "processing" || !syncId) return;

    const interval = setInterval(async () => {
      try {
        const res = await api.get<SyncSession>(`/api/v1/sync/${syncId}`);
        if (res.success && res.data) {
          setSyncStatus(res.data);
          if (res.data.status === "completed" || res.data.status === "failed") {
            clearInterval(interval);
            // Fetch discovered subscriptions
            const subRes = await api.get<Subscription[]>("/api/v1/subscriptions?limit=50");
            if (subRes.success && subRes.data) {
              setSubscriptions(subRes.data);
              const cost = subRes.data.reduce((sum, s) => sum + s.amount, 0);
              setTotalMonthlyCost(cost);
            }
            setStep("magic");
          }
        }
      } catch {
        // keep polling
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [step, syncId]);

  const handleConnectGmail = useCallback(async () => {
    try {
      const res = await api.post<{ id: string }>("/api/v1/connections", { source_type: "gmail" });
      if (res.success && res.data) {
        const connectionId = res.data.id;
        // Trigger manual sync — response has syncSessionId (not id)
        const syncRes = await api.post<{ syncSessionId: string; status: string; totalItems: number }>(
          `/api/v1/connections/${connectionId}/sync`, {}
        );
        if (syncRes.success && syncRes.data) {
          setSyncId(syncRes.data.syncSessionId);
        }
        setStep("sms");
      }
    } catch {
      setStep("sms");
    }
  }, []);

  const handleSkipToProcessing = useCallback(() => {
    if (skippedGmail && skippedSms) {
      // Both skipped — go straight to dashboard
      router.replace("/");
      return;
    }
    // If no syncId (e.g. Gmail skipped, SMS "set up"), skip processing
    if (!syncId) {
      setStep("magic");
      return;
    }
    setStep("processing");
  }, [skippedGmail, skippedSms, syncId, router]);

  const handleFinish = useCallback(() => {
    router.replace("/");
  }, [router]);

  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-6 bg-white">
      <div className="w-full max-w-md">
        {/* Progress indicator */}
        <div className="flex gap-1.5 mb-8">
          {(["education", "gmail", "sms", "processing", "magic"] as Step[]).map((s, i) => (
            <div
              key={s}
              className={`h-1 flex-1 rounded-full ${
                i <= ["education", "gmail", "sms", "processing", "magic"].indexOf(step)
                  ? "bg-blue-600"
                  : "bg-gray-200"
              }`}
            />
          ))}
        </div>

        {step === "education" && (
          <div className="text-center">
            <h2 className="text-2xl font-bold mb-3">Welcome to AdminOS</h2>
            <p className="text-gray-600 mb-6">
              AdminOS watches your bank transactions, Gmail, and uploaded statements to surface
              actionable insights — unused subscriptions, upcoming bills, anomalies, and weekly briefings.
            </p>
            <div className="bg-green-50 border border-green-200 rounded-lg p-4 mb-6 text-left">
              <h3 className="font-medium text-green-800 text-sm mb-2">🔒 Privacy first</h3>
              <p className="text-sm text-green-700">
                Raw SMS text never leaves your device. Only structured data (merchant, amount, date) is sent to our servers.
              </p>
            </div>
            <button
              onClick={() => setStep("gmail")}
              className="w-full py-3 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition-colors"
            >
              Get started
            </button>
          </div>
        )}

        {step === "gmail" && (
          <div className="text-center">
            <h2 className="text-2xl font-bold mb-3">Connect Gmail</h2>
            <p className="text-gray-600 mb-6">
              We scan your inbox for bank alerts, bill reminders, and subscription receipts to build your financial picture.
            </p>
            <button
              onClick={handleConnectGmail}
              className="w-full py-3 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition-colors mb-3"
            >
              Connect Gmail
            </button>
            <button
              onClick={() => {
                setSkippedGmail(true);
                setStep("sms");
              }}
              className="w-full py-3 text-gray-500 hover:text-gray-700 text-sm"
            >
              Skip for now
            </button>
          </div>
        )}

        {step === "sms" && (
          <div className="text-center">
            <h2 className="text-2xl font-bold mb-3">Enable SMS Parsing</h2>
            <p className="text-gray-600 mb-4">
              Install the AdminOS Android companion app to parse bank SMS messages on your device.
            </p>
            <div className="bg-gray-50 border border-gray-200 rounded-lg p-4 mb-6 text-left text-sm text-gray-600 space-y-2">
              <p>1. Download the AdminOS app from the Play Store</p>
              <p>2. Grant SMS read permission when prompted</p>
              <p>3. The app parses SMS locally and sends only structured data</p>
            </div>
            <button
              onClick={handleSkipToProcessing}
              className="w-full py-3 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition-colors mb-3"
            >
              {skippedGmail ? "Continue" : "I've set it up"}
            </button>
            <button
              onClick={() => {
                setSkippedSms(true);
                if (skippedGmail) {
                  router.replace("/");
                } else {
                  setStep("processing");
                }
              }}
              className="w-full py-3 text-gray-500 hover:text-gray-700 text-sm"
            >
              Skip for now
            </button>
          </div>
        )}

        {step === "processing" && (
          <div className="text-center">
            <h2 className="text-2xl font-bold mb-3">Analyzing your data...</h2>
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-blue-600 mx-auto mb-4" />
            {syncStatus && (
              <div className="space-y-2 text-sm text-gray-600">
                <p>Processed {syncStatus.processedItems} of {syncStatus.totalItems} items</p>
                {syncStatus.netNewItems > 0 && (
                  <p className="text-blue-600 font-medium">
                    Found {syncStatus.netNewItems} new transactions so far...
                  </p>
                )}
                {syncStatus.duplicateItems > 0 && (
                  <p>Skipped {syncStatus.duplicateItems} duplicates</p>
                )}
              </div>
            )}
            {!syncStatus && (
              <p className="text-sm text-gray-500">This may take a moment...</p>
            )}
          </div>
        )}

        {step === "magic" && (
          <div className="text-center">
            <h2 className="text-2xl font-bold mb-3">🎉 Here&apos;s what we found</h2>
            {subscriptions.length > 0 ? (
              <>
                <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 mb-4">
                  <p className="text-3xl font-bold text-blue-700">₹{totalMonthlyCost.toLocaleString("en-IN")}</p>
                  <p className="text-sm text-blue-600">monthly in subscriptions</p>
                </div>
                <p className="text-sm text-gray-600 mb-4">
                  {subscriptions.length} subscription{subscriptions.length !== 1 ? "s" : ""} detected
                  {subscriptions.filter((s) => s.isFlagged).length > 0 &&
                    ` · ${subscriptions.filter((s) => s.isFlagged).length} potentially unused`}
                </p>
                <div className="space-y-2 mb-6 max-h-48 overflow-y-auto">
                  {subscriptions.slice(0, 8).map((sub) => (
                    <div
                      key={sub.id}
                      className="flex items-center justify-between px-3 py-2 bg-gray-50 rounded-lg text-sm"
                    >
                      <span className="text-gray-700">{sub.name}</span>
                      <span className="font-medium">₹{sub.amount.toLocaleString("en-IN")}/{sub.billingCycle}</span>
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <p className="text-gray-600 mb-6">
                We&apos;re still processing your data. Check back on the dashboard for updates.
              </p>
            )}
            <button
              onClick={handleFinish}
              className="w-full py-3 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition-colors"
            >
              Go to Dashboard
            </button>
          </div>
        )}
      </div>
    </main>
  );
}
