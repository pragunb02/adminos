"use client";

import { useState, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import type { DashboardHome, ActionItem } from "@/lib/types";
import AppShell from "@/components/AppShell";
import ActionCard from "@/components/ActionCard";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

export default function Home() {
  const router = useRouter();
  const [data, setData] = useState<DashboardHome | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchDashboard = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<DashboardHome>("/api/v1/dashboard/home");
      if (res.success && res.data) {
        setData(res.data);
      } else {
        setError(res.error?.message || "Failed to load dashboard");
      }
    } catch {
      setError("Unable to connect to server");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchDashboard();
  }, [fetchDashboard]);

  const handleAction = (item: ActionItem) => {
    switch (item.type) {
      case "anomaly":
        router.push(`/anomalies/${item.entityId}`);
        break;
      case "subscription":
        router.push("/subscriptions");
        break;
      case "bill":
        router.push("/bills");
        break;
      case "briefing":
        router.push("/briefings");
        break;
    }
  };

  const severityForType = (type: string): "info" | "warning" | "critical" => {
    switch (type) {
      case "anomaly": return "critical";
      case "subscription": return "warning";
      case "bill": return "warning";
      default: return "info";
    }
  };

  const iconForType = (type: string) => {
    switch (type) {
      case "anomaly": return "⚠️";
      case "subscription": return "🔄";
      case "bill": return "📄";
      case "briefing": return "📊";
      default: return "📌";
    }
  };

  return (
    <AppShell>
      {loading && <LoadingSpinner message="Loading dashboard..." />}
      {error && <ErrorMessage message={error} onRetry={fetchDashboard} />}
      {data && !loading && (
        <div className="space-y-6">
          {/* Greeting */}
          <div>
            <h2 className="text-xl font-bold text-gray-900">{data.greeting}</h2>
          </div>

          {/* Summary cards */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <SummaryCard label="Spent this week" value={`₹${data.summary.totalSpentThisWeek.toLocaleString("en-IN")}`} />
            <SummaryCard label="Active subs" value={String(data.summary.activeSubscriptions)} />
            <SummaryCard label="Upcoming bills" value={String(data.summary.upcomingBills)} />
            <SummaryCard label="Open anomalies" value={String(data.summary.openAnomalies)} highlight={data.summary.openAnomalies > 0} />
          </div>

          {/* Action items */}
          {data.actionItems.length > 0 ? (
            <div className="space-y-3">
              <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">Needs attention</h3>
              {data.actionItems.map((item) => (
                <ActionCard
                  key={item.id}
                  title={item.title}
                  description={item.description}
                  severity={item.severity || severityForType(item.type)}
                  actionLabel={item.actionLabel}
                  onAction={() => handleAction(item)}
                  icon={iconForType(item.type)}
                />
              ))}
            </div>
          ) : (
            <div className="text-center py-8 bg-green-50 rounded-lg border border-green-200">
              <p className="text-2xl mb-2">✅</p>
              <p className="font-medium text-green-800">All clear!</p>
              <p className="text-sm text-green-600 mt-1">Your finances are in order. Nothing needs attention right now.</p>
            </div>
          )}

          {/* Latest briefing */}
          {data.latestBriefing && (
            <div className="bg-white border border-gray-200 rounded-lg p-4">
              <div className="flex items-center justify-between mb-2">
                <h3 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">Latest Briefing</h3>
                <button
                  onClick={() => router.push("/briefings")}
                  className="text-sm text-blue-600 hover:text-blue-800"
                >
                  View all
                </button>
              </div>
              <p className="text-sm text-gray-700 line-clamp-3">{data.latestBriefing.content}</p>
              {data.latestBriefing.totalSpent != null && (
                <p className="text-xs text-gray-500 mt-2">
                  Total spent: ₹{data.latestBriefing.totalSpent.toLocaleString("en-IN")}
                </p>
              )}
            </div>
          )}
        </div>
      )}
    </AppShell>
  );
}

function SummaryCard({ label, value, highlight = false }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className={`rounded-lg border p-3 ${highlight ? "border-red-200 bg-red-50" : "border-gray-200 bg-white"}`}>
      <p className={`text-lg font-bold ${highlight ? "text-red-700" : "text-gray-900"}`}>{value}</p>
      <p className="text-xs text-gray-500 mt-0.5">{label}</p>
    </div>
  );
}
