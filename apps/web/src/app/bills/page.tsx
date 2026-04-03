"use client";

import { useState, useEffect, useCallback } from "react";
import { api } from "@/lib/api";
import type { Bill } from "@/lib/types";
import AppShell from "@/components/AppShell";
import StatusBadge from "@/components/StatusBadge";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

export default function BillsPage() {
  const [bills, setBills] = useState<Bill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchBills = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<Bill[]>("/api/v1/bills/upcoming");
      if (res.success && res.data) {
        setBills(res.data);
      } else {
        setError(res.error?.message || "Failed to load bills");
      }
    } catch {
      setError("Unable to connect to server");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchBills();
  }, [fetchBills]);

  const daysUntilDue = (dueDate: string) => {
    const due = new Date(dueDate);
    const now = new Date();
    const diff = Math.ceil((due.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
    return diff;
  };

  const dueLabel = (dueDate: string, status: string) => {
    if (status === "paid") return "Paid";
    if (status === "overdue") return "Overdue";
    const days = daysUntilDue(dueDate);
    if (days === 0) return "Due today";
    if (days === 1) return "Due tomorrow";
    if (days < 0) return `${Math.abs(days)}d overdue`;
    return `Due in ${days}d`;
  };

  return (
    <AppShell>
      <div className="space-y-4">
        <h2 className="text-xl font-bold">Upcoming Bills</h2>

        {loading && <LoadingSpinner message="Loading bills..." />}
        {error && <ErrorMessage message={error} onRetry={fetchBills} />}

        {!loading && !error && bills.length === 0 && (
          <p className="text-center text-gray-500 py-8">No upcoming bills</p>
        )}

        {!loading && bills.length > 0 && (
          <div className="space-y-2">
            {bills.map((bill) => {
              const days = daysUntilDue(bill.dueDate);
              const isUrgent = days <= 3 && bill.status !== "paid";

              return (
                <div
                  key={bill.id}
                  className={`bg-white border rounded-lg p-4 ${
                    isUrgent ? "border-red-200" : "border-gray-200"
                  }`}
                >
                  <div className="flex items-start justify-between">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <p className="font-medium text-gray-900">{bill.billerName}</p>
                        <StatusBadge status={bill.status} />
                      </div>
                      <div className="flex items-center gap-3 mt-1 text-sm text-gray-500">
                        <span className="capitalize">{bill.billType.replace(/_/g, " ")}</span>
                        <span className={isUrgent ? "text-red-600 font-medium" : ""}>
                          {dueLabel(bill.dueDate, bill.status)}
                        </span>
                        <span>{new Date(bill.dueDate).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}</span>
                      </div>
                    </div>
                    <div className="text-right">
                      <p className="font-semibold text-gray-900">₹{bill.amount.toLocaleString("en-IN")}</p>
                      {bill.minimumDue != null && bill.minimumDue !== bill.amount && (
                        <p className="text-xs text-gray-400">Min: ₹{bill.minimumDue.toLocaleString("en-IN")}</p>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </AppShell>
  );
}
