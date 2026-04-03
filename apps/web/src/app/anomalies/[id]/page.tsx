"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams, useRouter } from "next/navigation";
import { api } from "@/lib/api";
import type { Anomaly } from "@/lib/types";
import AppShell from "@/components/AppShell";
import StatusBadge from "@/components/StatusBadge";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

export default function AnomalyDetailPage() {
  const params = useParams();
  const router = useRouter();
  const id = params.id as string;

  const [anomalies, setAnomalies] = useState<Anomaly[]>([]);
  const [selected, setSelected] = useState<Anomaly | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);

  const fetchAnomalies = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // Try fetching the specific anomaly first
      const singleRes = await api.get<Anomaly>(`/api/v1/anomalies/${id}`);
      if (singleRes.success && singleRes.data) {
        setSelected(singleRes.data);
      }

      // Also fetch the list for the sidebar
      const res = await api.get<Anomaly[]>("/api/v1/anomalies?status=open&limit=50");
      if (res.success && res.data) {
        setAnomalies(res.data);
        // If single fetch failed, fall back to finding in list
        if (!singleRes.success || !singleRes.data) {
          const match = res.data.find((a) => a.id === id);
          setSelected(match || res.data[0] || null);
        }
      } else if (!singleRes.success) {
        setError(res.error?.message || "Failed to load anomalies");
      }
    } catch {
      setError("Unable to connect to server");
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    fetchAnomalies();
  }, [fetchAnomalies]);

  const handleResolve = async (status: "confirmed_safe" | "confirmed_fraud") => {
    if (!selected) return;
    setActionLoading(true);
    try {
      const res = await api.patch<Anomaly>(`/api/v1/anomalies/${selected.id}`, { status });
      if (res.success) {
        // Remove from list and select next
        const remaining = anomalies.filter((a) => a.id !== selected.id);
        setAnomalies(remaining);
        setSelected(remaining[0] || null);
        if (remaining.length === 0) {
          router.push("/");
        }
      }
    } catch {
      // silent
    } finally {
      setActionLoading(false);
    }
  };

  const typeLabel = (type: string) => {
    switch (type) {
      case "large_amount": return "Unusually large amount";
      case "foreign_currency": return "Foreign currency charge";
      case "odd_hours": return "Odd hours transaction";
      case "card_testing": return "Possible card testing";
      case "duplicate_charge": return "Possible duplicate charge";
      default: return type.replace(/_/g, " ");
    }
  };

  return (
    <AppShell>
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <button onClick={() => router.push("/")} className="text-gray-400 hover:text-gray-600">
            ← Back
          </button>
          <h2 className="text-xl font-bold">Anomaly Review</h2>
        </div>

        {loading && <LoadingSpinner message="Loading anomalies..." />}
        {error && <ErrorMessage message={error} onRetry={fetchAnomalies} />}

        {!loading && !error && !selected && (
          <div className="text-center py-8 bg-green-50 rounded-lg border border-green-200">
            <p className="text-2xl mb-2">✅</p>
            <p className="font-medium text-green-800">No open anomalies</p>
            <p className="text-sm text-green-600 mt-1">All anomalies have been reviewed.</p>
          </div>
        )}

        {selected && (
          <div className="bg-white border border-gray-200 rounded-lg overflow-hidden">
            {/* Header */}
            <div className="bg-red-50 border-b border-red-100 p-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-xl">⚠️</span>
                  <h3 className="font-semibold text-gray-900">{typeLabel(selected.type)}</h3>
                </div>
                <StatusBadge status={selected.status} size="md" />
              </div>
              <p className="text-sm text-gray-600 mt-1">
                Confidence: {(selected.confidenceScore * 100).toFixed(0)}%
              </p>
            </div>

            {/* Details */}
            <div className="p-4 space-y-4">
              <div>
                <p className="text-xs font-semibold text-gray-500 uppercase mb-1">Reason</p>
                <p className="text-sm text-gray-700">{selected.reason}</p>
              </div>

              {selected.agentExplanation && (
                <div className="bg-blue-50 border border-blue-100 rounded-lg p-3">
                  <p className="text-xs font-semibold text-blue-600 uppercase mb-1">AI Explanation</p>
                  <p className="text-sm text-blue-800">{selected.agentExplanation}</p>
                </div>
              )}

              <div className="text-xs text-gray-400">
                Detected: {new Date(selected.createdAt).toLocaleString("en-IN")}
              </div>

              {/* Action buttons */}
              {selected.status === "open" && (
                <div className="flex gap-3 pt-2">
                  <button
                    onClick={() => handleResolve("confirmed_safe")}
                    disabled={actionLoading}
                    className="flex-1 py-3 text-sm font-medium text-green-700 bg-green-50 border border-green-200 rounded-lg hover:bg-green-100 disabled:opacity-50"
                  >
                    ✓ Confirmed Safe
                  </button>
                  <button
                    onClick={() => handleResolve("confirmed_fraud")}
                    disabled={actionLoading}
                    className="flex-1 py-3 text-sm font-medium text-red-700 bg-red-50 border border-red-200 rounded-lg hover:bg-red-100 disabled:opacity-50"
                  >
                    ✕ Confirmed Fraud
                  </button>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Other open anomalies */}
        {anomalies.length > 1 && (
          <div>
            <p className="text-xs font-semibold text-gray-500 uppercase mb-2">
              {anomalies.length - 1} more to review
            </p>
            <div className="space-y-2">
              {anomalies
                .filter((a) => a.id !== selected?.id)
                .map((a) => (
                  <button
                    key={a.id}
                    onClick={() => setSelected(a)}
                    className="w-full text-left bg-white border border-gray-200 rounded-lg p-3 hover:bg-gray-50"
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium text-gray-900">{typeLabel(a.type)}</span>
                      <span className="text-xs text-gray-400">
                        {(a.confidenceScore * 100).toFixed(0)}%
                      </span>
                    </div>
                    <p className="text-xs text-gray-500 mt-0.5 truncate">{a.reason}</p>
                  </button>
                ))}
            </div>
          </div>
        )}
      </div>
    </AppShell>
  );
}
