"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { errorMessage, getData, patchData, postData } from "@/lib/query";
import type { Subscription, SubscriptionSummary } from "@/lib/types";
import { formatInr } from "@/lib/money";
import AppShell from "@/components/AppShell";
import Mandates from "@/components/Mandates";
import StatusBadge from "@/components/StatusBadge";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

type Tab = "active" | "flagged" | "cancelled";

// "Flagged" is a flag on any subscription, not a status.
const TAB_QUERY: Record<Tab, string> = {
  active: "status=active",
  flagged: "flagged=true",
  cancelled: "status=cancelled",
};

/** The waste score's reasons (AOS-509), or the older one-line reason when it hasn't been scored. */
function whyFlagged(sub: Subscription): string | null {
  return sub.wasteReasons.length > 0 ? sub.wasteReasons.join("; ") : sub.flaggedReason;
}

export default function SubscriptionsPage() {
  const [tab, setTab] = useState<Tab>("active");
  /** "How to cancel" for one subscription (AOS-513): AdminOS can't cancel for you, so it shows where. */
  const [howTo, setHowTo] = useState<{ sub: Subscription; steps: string[] } | null>(null);
  const [howToError, setHowToError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const listQuery = useQuery({
    queryKey: ["subscriptions", "list", tab],
    queryFn: () => getData<Subscription[]>(`/api/v1/subscriptions?${TAB_QUERY[tab]}&limit=50`),
  });
  const { data: summary } = useQuery({
    queryKey: ["subscriptions", "summary"],
    queryFn: () => getData<SubscriptionSummary>("/api/v1/subscriptions/summary"),
  });
  const subscriptions = listQuery.data ?? [];
  const loading = listQuery.isPending;
  const error = listQuery.error ? errorMessage(listQuery.error, "Failed to load") : null;

  // "Keep" dismisses the flag and refreshes the lists.
  const keep = useMutation({
    mutationFn: (id: string) => patchData(`/api/v1/subscriptions/${id}/dismiss`, {}),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["subscriptions"] }),
  });
  // "How to cancel" asks the API for the steps (through the AutoPay mandate when one pays it).
  const guidance = useMutation({
    mutationFn: (sub: Subscription) => postData<{ steps: string[] }>(`/api/v1/subscriptions/${sub.id}/cancel`, {}),
    onSuccess: (data, sub) => { setHowToError(null); setHowTo({ sub, steps: data?.steps ?? [] }); },
    onError: (e) => setHowToError(errorMessage(e, "Couldn't load the cancel steps")),
  });
  const actionLoading = keep.isPending ? keep.variables : guidance.isPending ? guidance.variables?.id : null;
  const handleKeep = (id: string) => keep.mutate(id);

  const tabs: { key: Tab; label: string; count?: number }[] = [
    { key: "active", label: "Active", count: summary ? Number(summary.activeCount) : undefined },
    { key: "flagged", label: "Flagged", count: summary ? Number(summary.flaggedCount) : undefined },
    { key: "cancelled", label: "Cancelled", count: summary ? Number(summary.cancelledCount) : undefined },
  ];

  return (
    <AppShell>
      <div className="space-y-4">
        <h2 className="text-xl font-bold">Subscriptions</h2>

        {/* Summary */}
        {summary && (
          <div className="bg-white border border-gray-200 rounded-lg p-4 flex items-center justify-between">
            <div>
              <p className="text-2xl font-bold text-gray-900">{formatInr(summary.totalMonthlyCost)}</p>
              <p className="text-sm text-gray-500">total monthly cost</p>
            </div>
            <div className="text-right text-sm text-gray-500 space-y-0.5">
              <p>{summary.activeCount} active</p>
              {summary.flaggedCount > 0 && (
                <p className="text-orange-700 font-medium">{summary.flaggedCount} flagged</p>
              )}
            </div>
          </div>
        )}

        {/* Tabs */}
        <div className="flex gap-1 bg-gray-100 rounded-lg p-1">
          {tabs.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`flex-1 py-2 text-sm font-medium rounded-md transition-colors ${
                tab === t.key ? "bg-white text-gray-900 shadow-xs" : "text-gray-600 hover:text-gray-800"
              }`}
            >
              {t.label}
              {t.count != null && <span className="ml-1 text-xs text-gray-600">({t.count})</span>}
            </button>
          ))}
        </div>

        {/* List */}
        {loading && <LoadingSpinner />}
        {error && <ErrorMessage message={error} onRetry={() => listQuery.refetch()} />}
        {keep.error && <ErrorMessage message={errorMessage(keep.error)} />}
        {!loading && !error && subscriptions.length === 0 && (
          <p className="text-center text-gray-500 py-8">No {tab} subscriptions</p>
        )}
        {!loading && subscriptions.length > 0 && (
          <div className="space-y-2">
            {subscriptions.map((sub) => (
              <div key={sub.id} className="bg-white border border-gray-200 rounded-lg p-4">
                <div className="flex items-start justify-between">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <p className="font-medium text-gray-900">{sub.name}</p>
                      <StatusBadge status={sub.status} />
                      {sub.priceChanged && (
                        <span className="text-xs text-orange-700">
                          {sub.priceChangePct == null
                            ? "price changed"
                            : `${sub.priceChangePct > 0 ? "↑" : "↓"}${Math.abs(sub.priceChangePct).toFixed(0)}%`}
                        </span>
                      )}
                    </div>
                    <p className="text-sm text-gray-500 mt-1">
                      {formatInr(sub.amount)} / {sub.billingCycle}
                      {sub.nextBillingDate && ` · Next: ${sub.nextBillingDate}`}
                    </p>
                    {sub.isFlagged && whyFlagged(sub) && (
                      <p className="text-xs text-orange-700 mt-1">
                        <span className="font-medium">Why flagged:</span> {whyFlagged(sub)}
                      </p>
                    )}
                  </div>
                  <p className="font-semibold text-gray-900 whitespace-nowrap">
                    {formatInr(sub.amount)}
                  </p>
                </div>

                {sub.isFlagged && (
                  <div className="flex gap-2 mt-3 pt-3 border-t border-gray-100">
                    <button
                      onClick={() => guidance.mutate(sub)}
                      disabled={actionLoading === sub.id}
                      className="flex-1 py-2 text-sm font-medium text-red-700 bg-red-50 rounded-md hover:bg-red-100 disabled:opacity-50"
                    >
                      How to cancel
                    </button>
                    <button
                      onClick={() => handleKeep(sub.id)}
                      disabled={actionLoading === sub.id}
                      className="flex-1 py-2 text-sm font-medium text-gray-700 bg-gray-50 rounded-md hover:bg-gray-100 disabled:opacity-50"
                    >
                      Keep
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        {/* AutoPay & mandates (AOS-502) */}
        <Mandates />
      </div>

      {howToError && <p role="alert" className="fixed inset-x-4 bottom-24 z-50 mx-auto max-w-md rounded-lg bg-white p-3 text-sm text-red-700 shadow">{howToError}</p>}

      {/* How to cancel (AOS-513) */}
      {howTo && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => setHowTo(null)}>
          <div role="dialog" aria-modal="true" aria-labelledby="howto-heading" className="bg-white rounded-lg shadow-lg w-full max-w-sm p-5" onClick={(e) => e.stopPropagation()}
            onKeyDown={(e) => { if (e.key === "Escape") setHowTo(null); }}>
            <h3 id="howto-heading" className="font-semibold text-gray-900 mb-2">How to cancel {howTo.sub.name}</h3>
            <ol className="list-decimal space-y-2 pl-5 text-sm text-gray-700">
              {howTo.steps.map((s) => <li key={s}>{s}</li>)}
            </ol>
            <button autoFocus onClick={() => setHowTo(null)}
              className="mt-4 w-full py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200">
              Close
            </button>
          </div>
        </div>
      )}
    </AppShell>
  );
}
