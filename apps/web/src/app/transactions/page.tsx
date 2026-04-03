"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { api } from "@/lib/api";
import type { Transaction } from "@/lib/types";
import AppShell from "@/components/AppShell";
import StatusBadge from "@/components/StatusBadge";
import LoadingSpinner from "@/components/LoadingSpinner";
import ErrorMessage from "@/components/ErrorMessage";

const CATEGORIES = [
  "all", "food", "transport", "shopping", "utilities", "emi", "subscription",
  "entertainment", "health", "education", "transfer", "other",
];

export default function TransactionsPage() {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  // Filters
  const [category, setCategory] = useState("all");
  const [type, setType] = useState("all");
  const [merchantSearch, setMerchantSearch] = useState("");
  const [debouncedMerchant, setDebouncedMerchant] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [recurring, setRecurring] = useState(false);

  // Debounce merchant search (300ms)
  const merchantTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (merchantTimerRef.current) clearTimeout(merchantTimerRef.current);
    merchantTimerRef.current = setTimeout(() => {
      setDebouncedMerchant(merchantSearch);
    }, 300);
    return () => {
      if (merchantTimerRef.current) clearTimeout(merchantTimerRef.current);
    };
  }, [merchantSearch]);

  // Reset cursor when filters change
  useEffect(() => {
    setCursor(null);
  }, [category, type, debouncedMerchant, dateFrom, dateTo, recurring]);

  const buildQuery = useCallback(
    (cursorVal?: string | null) => {
      const params = new URLSearchParams();
      if (category !== "all") params.set("category", category);
      if (type !== "all") params.set("type", type);
      if (debouncedMerchant) params.set("merchant", debouncedMerchant);
      if (dateFrom) params.set("from", dateFrom);
      if (dateTo) params.set("to", dateTo);
      if (recurring) params.set("is_recurring", "true");
      if (cursorVal) params.set("cursor", cursorVal);
      params.set("limit", "20");
      return `/api/v1/transactions?${params}`;
    },
    [category, type, debouncedMerchant, dateFrom, dateTo, recurring]
  );

  const fetchTransactions = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<Transaction[]>(buildQuery());
      if (res.success && res.data) {
        setTransactions(res.data);
        setCursor(res.pagination?.cursor || null);
        setHasMore(res.pagination?.hasMore || false);
      } else {
        setError(res.error?.message || "Failed to load transactions");
      }
    } catch {
      setError("Unable to connect to server");
    } finally {
      setLoading(false);
    }
  }, [buildQuery]);

  useEffect(() => {
    fetchTransactions();
  }, [fetchTransactions]);

  const loadMore = async () => {
    if (!cursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const res = await api.get<Transaction[]>(buildQuery(cursor));
      if (res.success && res.data) {
        setTransactions((prev) => [...prev, ...res.data!]);
        setCursor(res.pagination?.cursor || null);
        setHasMore(res.pagination?.hasMore || false);
      }
    } catch {
      // silent
    } finally {
      setLoadingMore(false);
    }
  };

  const formatDate = (iso: string) => {
    const d = new Date(iso);
    return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
  };

  return (
    <AppShell>
      <div className="space-y-4">
        <h2 className="text-xl font-bold">Transactions</h2>

        {/* Filters */}
        <div className="bg-white border border-gray-200 rounded-lg p-3 space-y-3">
          <div className="flex flex-wrap gap-2">
            <input
              type="text"
              placeholder="Search merchant..."
              value={merchantSearch}
              onChange={(e) => setMerchantSearch(e.target.value)}
              className="flex-1 min-w-[150px] px-3 py-1.5 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <select
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              className="px-3 py-1.5 border border-gray-300 rounded-md text-sm bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {CATEGORIES.map((c) => (
                <option key={c} value={c}>{c === "all" ? "All categories" : c}</option>
              ))}
            </select>
            <select
              value={type}
              onChange={(e) => setType(e.target.value)}
              className="px-3 py-1.5 border border-gray-300 rounded-md text-sm bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="all">All types</option>
              <option value="debit">Debit</option>
              <option value="credit">Credit</option>
            </select>
          </div>
          <div className="flex flex-wrap gap-2 items-center">
            <input
              type="date"
              value={dateFrom}
              onChange={(e) => setDateFrom(e.target.value)}
              className="px-3 py-1.5 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <span className="text-gray-400 text-sm">to</span>
            <input
              type="date"
              value={dateTo}
              onChange={(e) => setDateTo(e.target.value)}
              className="px-3 py-1.5 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <label className="flex items-center gap-1.5 text-sm text-gray-600 cursor-pointer">
              <input
                type="checkbox"
                checked={recurring}
                onChange={(e) => setRecurring(e.target.checked)}
                className="rounded border-gray-300"
              />
              Recurring only
            </label>
          </div>
        </div>

        {/* Transaction list */}
        {loading && <LoadingSpinner message="Loading transactions..." />}
        {error && <ErrorMessage message={error} onRetry={fetchTransactions} />}
        {!loading && !error && transactions.length === 0 && (
          <p className="text-center text-gray-500 py-8">No transactions found</p>
        )}
        {!loading && transactions.length > 0 && (
          <div className="space-y-2">
            {transactions.map((txn) => (
              <div key={txn.id} className="bg-white border border-gray-200 rounded-lg p-3 flex items-center justify-between">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <p className="font-medium text-sm text-gray-900 truncate">{txn.merchantName || "Unknown"}</p>
                    {txn.isAnomaly && <span className="text-xs text-red-600">⚠️</span>}
                    {txn.isRecurring && <span className="text-xs text-blue-600">🔄</span>}
                  </div>
                  <div className="flex items-center gap-2 mt-1">
                    <StatusBadge status={txn.category} />
                    <span className="text-xs text-gray-400">{formatDate(txn.transactedAt)}</span>
                    {txn.accountLast4 && <span className="text-xs text-gray-400">••{txn.accountLast4}</span>}
                    {txn.paymentMethod && <span className="text-xs text-gray-400">{txn.paymentMethod}</span>}
                  </div>
                </div>
                <p className={`font-semibold text-sm whitespace-nowrap ${txn.type === "credit" ? "text-green-600" : "text-gray-900"}`}>
                  {txn.type === "credit" ? "+" : "-"}₹{txn.amount.toLocaleString("en-IN")}
                </p>
              </div>
            ))}

            {hasMore && (
              <button
                onClick={loadMore}
                disabled={loadingMore}
                className="w-full py-2.5 text-sm font-medium text-blue-600 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 disabled:opacity-50"
              >
                {loadingMore ? "Loading..." : "Load more"}
              </button>
            )}
          </div>
        )}
      </div>
    </AppShell>
  );
}
