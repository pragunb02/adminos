"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { errorMessage, getData } from "@/lib/query";
import type { Briefing } from "@/lib/types";
import { formatInr } from "@/lib/money";
import AppShell from "@/components/AppShell";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

export default function BriefingsPage() {
  const router = useRouter();
  // The latest briefing may not exist yet; that is not an error.
  const latestQuery = useQuery({
    queryKey: ["briefings", "latest"],
    queryFn: () => getData<Briefing | null>("/api/v1/briefings/latest").catch(() => null),
  });
  const historyQuery = useQuery({
    queryKey: ["briefings", "history"],
    queryFn: () => getData<Briefing[]>("/api/v1/briefings?limit=20"),
  });
  const latest = latestQuery.data ?? null;
  const history = (historyQuery.data ?? []).filter((b) => b.id !== latest?.id);
  const loading = latestQuery.isPending || historyQuery.isPending;
  const error = historyQuery.error && !latest ? errorMessage(historyQuery.error, "Failed to load briefings") : null;
  const fetchData = () => {
    latestQuery.refetch();
    historyQuery.refetch();
  };

  const handleInsightAction = (actionType: string, entityId: string | null) => {
    if (!entityId) return;
    switch (actionType) {
      case "cancel_sub":
        router.push("/subscriptions");
        break;
      case "review_anomaly":
        router.push(`/anomalies/${entityId}`);
        break;
      case "pay_bill":
        router.push("/bills");
        break;
    }
  };

  const formatPeriod = (start: string, end: string) => {
    const s = new Date(start);
    const e = new Date(end);
    return `${s.toLocaleDateString("en-IN", { day: "numeric", month: "short" })} – ${e.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })}`;
  };

  const actionLabel = (type: string) => {
    switch (type) {
      case "cancel_sub": return "Review sub";
      case "review_anomaly": return "Review";
      case "pay_bill": return "View bill";
      default: return "View";
    }
  };

  return (
    <AppShell>
      <div className="space-y-6">
        <h2 className="text-xl font-bold">Briefings</h2>

        {loading && <LoadingSpinner message="Loading briefings..." />}
        {error && <ErrorMessage message={error} onRetry={fetchData} />}

        {!loading && !error && !latest && history.length === 0 && (
          <p className="text-center text-gray-500 py-8">No briefings yet. Your first weekly briefing will appear here.</p>
        )}

        {/* Latest briefing — expanded */}
        {latest && (
          <div className="bg-white border border-gray-200 rounded-lg p-5">
            <div className="flex items-center justify-between mb-3">
              <h3 className="font-semibold text-gray-900">Latest Briefing</h3>
              <span className="text-xs text-gray-500">{formatPeriod(latest.periodStart, latest.periodEnd)}</span>
            </div>

            {/* Stats row */}
            <div className="flex gap-4 mb-4 text-sm">
              {latest.totalSpent != null && (
                <div>
                  <span className="text-gray-500">Spent: </span>
                  <span className="font-medium">{formatInr(latest.totalSpent)}</span>
                </div>
              )}
              {latest.totalIncome != null && (
                <div>
                  <span className="text-gray-500">Income: </span>
                  <span className="font-medium text-green-700">{formatInr(latest.totalIncome)}</span>
                </div>
              )}
            </div>

            {/* Content */}
            <div className="prose prose-sm max-w-none text-gray-700 whitespace-pre-line mb-4">
              {latest.content}
            </div>

            {/* Top categories */}
            {latest.topCategories && latest.topCategories.length > 0 && (
              <div className="mb-4">
                <p className="text-xs font-semibold text-gray-500 uppercase mb-2">Top spending</p>
                <div className="flex flex-wrap gap-2">
                  {latest.topCategories.map((cat) => (
                    <span key={cat.category} className="text-xs bg-gray-100 text-gray-700 px-2 py-1 rounded-full">
                      {cat.category}: {formatInr(cat.amount)}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {/* Insights with actions */}
            {latest.insights && latest.insights.length > 0 && (
              <div className="border-t border-gray-100 pt-4 space-y-2">
                <p className="text-xs font-semibold text-gray-500 uppercase mb-2">Action items</p>
                {latest.insights.map((insight) => (
                  <div key={insight.id} className="flex items-center justify-between bg-gray-50 rounded-lg p-3">
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-gray-900">{insight.title}</p>
                      <p className="text-xs text-gray-500 mt-0.5">{insight.body}</p>
                    </div>
                    {insight.actionType !== "none" && (
                      <button
                        onClick={() => handleInsightAction(insight.actionType, insight.entityId)}
                        className="shrink-0 ml-3 text-xs font-medium px-3 py-1.5 rounded-md bg-white border border-gray-200 hover:bg-gray-50"
                      >
                        {actionLabel(insight.actionType)}
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* History */}
        {history.length > 0 && (
          <div>
            <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">Previous briefings</h3>
            <div className="space-y-2">
              {history.map((b) => (
                <div key={b.id} className="bg-white border border-gray-200 rounded-lg p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-gray-900">
                      {formatPeriod(b.periodStart, b.periodEnd)}
                    </span>
                    {b.totalSpent != null && (
                      <span className="text-sm text-gray-500">{formatInr(b.totalSpent)}</span>
                    )}
                  </div>
                  <p className="text-sm text-gray-600 mt-1 line-clamp-2">{b.content}</p>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </AppShell>
  );
}
