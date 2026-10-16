"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { errorMessage, getData, patchData } from "@/lib/query";
import type { Anomaly } from "@/lib/types";
import AppShell from "@/components/AppShell";
import StatusBadge from "@/components/StatusBadge";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

export default function AnomalyDetailPage() {
  const params = useParams();
  const router = useRouter();
  const id = params.id as string;

  const [selectedId, setSelectedId] = useState(id);
  const queryClient = useQueryClient();

  const listQuery = useQuery({
    queryKey: ["anomalies", "open"],
    queryFn: () => getData<Anomaly[]>("/api/v1/anomalies?status=open&limit=50"),
  });
  const singleQuery = useQuery({
    queryKey: ["anomalies", "one", selectedId],
    queryFn: () => getData<Anomaly>(`/api/v1/anomalies/${selectedId}`),
  });
  const anomalies = listQuery.data ?? [];
  // The one asked for, else the first open one from the list.
  const selected = singleQuery.data ?? anomalies.find((a) => a.id === selectedId) ?? anomalies[0] ?? null;
  const loading = listQuery.isPending && singleQuery.isPending;
  const error = listQuery.error && !singleQuery.data ? errorMessage(listQuery.error, "Failed to load anomalies") : null;
  const fetchAnomalies = () => {
    listQuery.refetch();
    singleQuery.refetch();
  };

  const resolve = useMutation({
    mutationFn: ({ anomalyId, status }: { anomalyId: string; status: "confirmed_safe" | "confirmed_fraud" }) =>
      patchData<Anomaly>(`/api/v1/anomalies/${anomalyId}`, { status }),
    onSuccess: (_, { anomalyId }) => {
      const remaining = anomalies.filter((a) => a.id !== anomalyId);
      queryClient.invalidateQueries({ queryKey: ["anomalies"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
      if (remaining.length === 0) router.push("/");
      else setSelectedId(remaining[0].id);
    },
  });
  const actionLoading = resolve.isPending;
  const handleResolve = (status: "confirmed_safe" | "confirmed_fraud") => {
    if (selected) resolve.mutate({ anomalyId: selected.id, status });
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
          <button onClick={() => router.push("/")} className="text-gray-500 hover:text-gray-600">
            ← Back
          </button>
          <h2 className="text-xl font-bold">Anomaly Review</h2>
        </div>

        {loading && <LoadingSpinner message="Loading anomalies..." />}
        {error && <ErrorMessage message={error} onRetry={fetchAnomalies} />}
        {resolve.error && <ErrorMessage message={errorMessage(resolve.error)} />}

        {!loading && !error && !selected && (
          <div className="text-center py-8 bg-green-50 rounded-lg border border-green-200">
            <p className="text-2xl mb-2">✅</p>
            <p className="font-medium text-green-800">No open anomalies</p>
            <p className="text-sm text-green-700 mt-1">All anomalies have been reviewed.</p>
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

              <div className="text-xs text-gray-500">
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
                    onClick={() => setSelectedId(a.id)}
                    className="w-full text-left bg-white border border-gray-200 rounded-lg p-3 hover:bg-gray-50"
                  >
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium text-gray-900">{typeLabel(a.type)}</span>
                      <span className="text-xs text-gray-500">
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
