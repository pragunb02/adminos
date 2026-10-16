"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";

const CATEGORIES = [
  { value: "food", label: "Food", icon: "🍽️" },
  { value: "transport", label: "Transport", icon: "🛺" },
  { value: "shopping", label: "Shopping", icon: "🛍️" },
  { value: "health", label: "Health", icon: "💊" },
  { value: "entertainment", label: "Fun", icon: "🎬" },
  { value: "utilities", label: "Bills", icon: "💡" },
  { value: "education", label: "Education", icon: "📚" },
  { value: "other", label: "Other", icon: "•••" },
] as const;

const AMOUNT = /^\d{1,8}(\.\d{1,2})?$/;

/**
 * Cash quick-add (AOS-204): "+ Cash" → type the amount → tap a category, and it's saved.
 * One clientRef per entry, so a double tap or a retry after a network error is saved once.
 * ATM withdrawals count as moving money to cash (D-42), so only what's logged here is spending.
 */
export default function CashQuickAdd() {
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const [clientRef, setClientRef] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const amountRef = useRef<HTMLInputElement>(null);

  const start = () => {
    setAmount("");
    setNote("");
    setError(null);
    setClientRef(crypto.randomUUID());
    setOpen(true);
  };

  useEffect(() => {
    if (!open) return;
    amountRef.current?.focus();
    // Escape closes the sheet wherever focus is (not only inside it).
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  useEffect(() => {
    if (!saved) return;
    const t = setTimeout(() => setSaved(null), 2500);
    return () => clearTimeout(t);
  }, [saved]);

  const valid = AMOUNT.test(amount) && Number(amount) > 0;

  const save = async (category: string) => {
    if (!valid || saving) return;
    setSaving(true);
    setError(null);
    const res = await api.post<{ saved: boolean; duplicate: boolean }>("/api/v1/transactions/cash", {
      clientRef,
      amount,
      category,
      note: note.trim() || undefined,
      occurredAt: new Date().toISOString(),
    });
    setSaving(false);
    if (res.success) {
      setOpen(false);
      setSaved(`₹${amount} cash saved`);
    } else setError(res.error?.message ?? "Couldn't save. Try again.");
  };

  return (
    <>
      <button
        type="button"
        onClick={start}
        className="fixed right-4 bottom-20 sm:bottom-6 z-40 rounded-full bg-emerald-700 px-5 py-3 text-white font-semibold shadow-lg hover:bg-emerald-800"
        aria-label="Add cash spending"
      >
        + Cash
      </button>

      {saved && (
        <div role="status" className="fixed left-1/2 -translate-x-1/2 bottom-36 sm:bottom-20 z-50 rounded-md bg-gray-900 px-4 py-2 text-sm text-white">
          {saved}
        </div>
      )}

      {open && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/40" onClick={() => setOpen(false)}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Add cash spending"
            className="w-full sm:max-w-md rounded-t-2xl sm:rounded-2xl bg-white p-5 space-y-4"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2 text-3xl font-bold">
              <span className="text-gray-500">₹</span>
              <input
                ref={amountRef}
                inputMode="decimal"
                placeholder="0"
                aria-label="Amount in rupees"
                value={amount}
                onChange={(e) => setAmount(e.target.value.replace(/[^\d.]/g, ""))}
                className="w-full outline-none"
              />
            </div>
            <input
              placeholder="Note (optional), e.g. chai"
              aria-label="Note"
              maxLength={255}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              className="w-full rounded-md border border-gray-200 px-3 py-2 text-sm"
            />
            <div className="grid grid-cols-4 gap-2">
              {CATEGORIES.map((c) => (
                <button
                  key={c.value}
                  type="button"
                  disabled={!valid || saving}
                  onClick={() => save(c.value)}
                  className="flex flex-col items-center gap-1 rounded-lg border border-gray-200 py-3 text-xs hover:bg-emerald-50 disabled:opacity-40"
                >
                  <span className="text-xl" aria-hidden>
                    {c.icon}
                  </span>
                  {c.label}
                </button>
              ))}
            </div>
            <p className="text-xs text-gray-500">{saving ? "Saving…" : valid ? "Tap a category to save." : "Type the amount first."}</p>
            {error && (
              <p role="alert" className="text-sm text-red-600">
                {error}
              </p>
            )}
          </div>
        </div>
      )}
    </>
  );
}
