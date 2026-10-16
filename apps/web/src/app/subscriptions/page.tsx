"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { errorMessage, getData, patchData, postData } from "@/lib/query";
import type { Subscription, SubscriptionSummary } from "@/lib/types";
import AppShell from "@/components/AppShell";
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

export default function SubscriptionsPage() {
  const [tab, setTab] = useState<Tab>("active");
  const [cancelDraft, setCancelDraft] = useState<Subscription | null>(null);
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

  // Cancel queues a draft-generation job; "keep" dismisses the flag. Both refresh the lists.
  const action = useMutation({
    mutationFn: ({ id, kind }: { id: string; kind: "cancel" | "keep" }) =>
      kind === "cancel" ? postData(`/api/v1/subscriptions/${id}/cancel`, {}) : patchData(`/api/v1/subscriptions/${id}/dismiss`, {}),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["subscriptions"] }),
  });
  const actionLoading = action.isPending ? action.variables?.id : null;
  const handleCancel = (id: string) => action.mutate({ id, kind: "cancel" });
  const handleKeep = (id: string) => action.mutate({ id, kind: "keep" });

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
              <p className="text-2xl font-bold text-gray-900">₹{summary.totalMonthlyCost.toLocaleString("en-IN")}</p>
              <p className="text-sm text-gray-500">total monthly cost</p>
            </div>
            <div className="text-right text-sm text-gray-500 space-y-0.5">
              <p>{summary.activeCount} active</p>
              {summary.flaggedCount > 0 && (
                <p className="text-orange-600 font-medium">{summary.flaggedCount} flagged</p>
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
                tab === t.key ? "bg-white text-gray-900 shadow-xs" : "text-gray-500 hover:text-gray-700"
              }`}
            >
              {t.label}
              {t.count != null && <span className="ml-1 text-xs text-gray-400">({t.count})</span>}
            </button>
          ))}
        </div>

        {/* List */}
        {loading && <LoadingSpinner />}
        {error && <ErrorMessage message={error} onRetry={() => listQuery.refetch()} />}
        {action.error && <ErrorMessage message={errorMessage(action.error)} />}
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
                        <span className="text-xs text-orange-600">
                          {sub.priceChangePct == null
                            ? "price changed"
                            : `${sub.priceChangePct > 0 ? "↑" : "↓"}${Math.abs(sub.priceChangePct).toFixed(0)}%`}
                        </span>
                      )}
                    </div>
                    <p className="text-sm text-gray-500 mt-1">
                      ₹{sub.amount.toLocaleString("en-IN")} / {sub.billingCycle}
                      {sub.nextBillingDate && ` · Next: ${sub.nextBillingDate}`}
                    </p>
                    {sub.isFlagged && sub.flaggedReason && (
                      <p className="text-xs text-orange-600 mt-1">{sub.flaggedReason}</p>
                    )}
                  </div>
                  <p className="font-semibold text-gray-900 whitespace-nowrap">
                    ₹{sub.amount.toLocaleString("en-IN")}
                  </p>
                </div>

                {sub.isFlagged && (
                  <div className="flex gap-2 mt-3 pt-3 border-t border-gray-100">
                    <button
                      onClick={() => handleCancel(sub.id)}
                      disabled={actionLoading === sub.id}
                      className="flex-1 py-2 text-sm font-medium text-red-600 bg-red-50 rounded-md hover:bg-red-100 disabled:opacity-50"
                    >
                      Cancel
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
      </div>

      {/* Cancel draft modal */}
      {cancelDraft && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
          <div className="bg-white rounded-lg shadow-lg w-full max-w-sm p-5">
            <h3 className="font-semibold text-gray-900 mb-2">Cancellation Draft</h3>
            <div className="space-y-2 text-sm text-gray-700">
              <p><span className="font-medium">Subscription:</span> {cancelDraft.name}</p>
              <p><span className="font-medium">Amount:</span> ₹{cancelDraft.amount.toLocaleString("en-IN")} / {cancelDraft.billingCycle}</p>
              <p><span className="font-medium">Status:</span> {cancelDraft.status.replace(/_/g, " ")}</p>
              {cancelDraft.nextBillingDate && (
                <p><span className="font-medium">Next billing:</span> {cancelDraft.nextBillingDate}</p>
              )}
            </div>
            <button
              onClick={() => setCancelDraft(null)}
              className="mt-4 w-full py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200"
            >
              Close
            </button>
          </div>
        </div>
      )}
    </AppShell>
  );
}
