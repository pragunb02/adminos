"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import { Skeleton } from "@/components/console/Section";
import { categoryLabel } from "@/lib/analytics";
import { formatInr } from "@/lib/money";
import { deleteData, errorMessage, getData, patchData, postData } from "@/lib/query";
import { ROLE, modesLabel, tripDates } from "@/lib/trips";
import type { Transaction, TripDetail } from "@/lib/types";

const when = (iso: string) => new Date(iso).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" });

/**
 * One trip (AOS-910): its bookings and the spends around it, one total. The owner can rename it, add a
 * nearby payment, remove one (it never comes back by itself), or say it isn't a trip.
 */
export default function TripDetailPage() {
  const id = String(useParams().id);
  const router = useRouter();
  const qc = useQueryClient();
  const trip = useQuery({ queryKey: ["trips", "one", id], queryFn: () => getData<TripDetail>(`/api/v1/trips/${id}`) });
  const [adding, setAdding] = useState(false);
  const candidates = useQuery({ queryKey: ["trips", "candidates", id], queryFn: () => getData<Transaction[]>(`/api/v1/trips/${id}/candidates`), enabled: adding });
  const [name, setName] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const done = () => {
    setFailure(null);
    qc.invalidateQueries({ queryKey: ["trips"] });
  };
  const fail = (e: unknown) => setFailure(errorMessage(e));
  const rename = useMutation({ mutationFn: (n: string) => patchData(`/api/v1/trips/${id}`, { name: n }), onSuccess: () => { setName(null); done(); }, onError: fail });
  const add = useMutation({ mutationFn: (t: string) => postData(`/api/v1/trips/${id}/transactions`, { transactionId: t }), onSuccess: done, onError: fail });
  const remove = useMutation({ mutationFn: (t: string) => deleteData(`/api/v1/trips/${id}/transactions/${t}`), onSuccess: done, onError: fail });
  const dismiss = useMutation({
    mutationFn: () => deleteData(`/api/v1/trips/${id}`),
    onSuccess: () => { done(); router.push("/trips"); },
    onError: fail,
  });

  return (
    <AppShell>
      <div className="space-y-4">
        <Link href="/trips" className="text-sm text-ink-muted underline">‹ All trips</Link>
        {trip.isPending ? (
          <Skeleton lines={5} />
        ) : trip.error ? (
          <p role="alert" className="text-sm text-negative">{errorMessage(trip.error)} <button type="button" className="underline" onClick={() => trip.refetch()}>Try again</button></p>
        ) : (
          <>
            <div>
              {name === null ? (
                <div className="flex items-start justify-between gap-2">
                  <h2 className="text-xl font-bold">{trip.data.trip.name}</h2>
                  <button type="button" className="h-9 rounded-lg border border-line px-3 text-sm" onClick={() => setName(trip.data.trip.name)}>Rename</button>
                </div>
              ) : (
                <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (name.trim()) rename.mutate(name.trim()); }}>
                  <label className="sr-only" htmlFor="trip-name">Trip name</label>
                  <input id="trip-name" value={name} maxLength={80} onChange={(e) => setName(e.target.value)} className="h-11 flex-1 rounded-lg border border-line px-3 text-sm" />
                  <button type="submit" disabled={!name.trim() || rename.isPending} className="h-11 rounded-lg bg-accent px-4 text-sm font-semibold text-white disabled:opacity-50">Save</button>
                  <button type="button" onClick={() => setName(null)} className="h-11 rounded-lg border border-line px-3 text-sm">Cancel</button>
                </form>
              )}
              <p className="text-sm text-ink-muted">{tripDates(trip.data.trip.start, trip.data.trip.end)} · {modesLabel(trip.data.trip.modes)}</p>
              <p className="tabular mt-1 text-2xl font-bold" aria-label="Trip total">{formatInr(trip.data.trip.total)}</p>
            </div>
            {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}
            <ul aria-label="Payments in this trip" className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
              {trip.data.members.map((m) => (
                <li key={m.transaction.id} className="flex items-center justify-between gap-3 px-4 py-3">
                  <span className="min-w-0">
                    <span className="block truncate text-sm">{m.transaction.merchantName || "Unknown"}</span>
                    <span className="block text-xs text-ink-muted">{ROLE[m.role]} · {when(m.transaction.transactedAt)} · {categoryLabel(m.transaction.category)}{m.refunded !== "0.00" ? ` · ${formatInr(m.refunded)} refunded` : ""}</span>
                  </span>
                  <span className="flex items-center gap-2">
                    <span className="tabular text-sm">{formatInr(m.transaction.amount)}</span>
                    <button type="button" className="h-9 rounded-lg border border-line px-2 text-xs" aria-label={`Remove ${m.transaction.merchantName || "payment"} from this trip`}
                      onClick={() => remove.mutate(m.transaction.id)}>Remove</button>
                  </span>
                </li>
              ))}
            </ul>
            {adding ? (
              <section aria-labelledby="nearby" className="space-y-2">
                <h3 id="nearby" className="text-sm font-semibold">Payments around these dates</h3>
                {candidates.isPending ? <Skeleton lines={3} /> : candidates.error ? (
                  <p role="alert" className="text-sm text-negative">{errorMessage(candidates.error)}</p>
                ) : candidates.data.length === 0 ? (
                  <p className="text-sm text-ink-muted">No other payments around these dates.</p>
                ) : (
                  <ul aria-label="Payments to add" className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
                    {candidates.data.map((t) => (
                      <li key={t.id} className="flex items-center justify-between gap-3 px-4 py-3">
                        <span className="min-w-0 truncate text-sm">{t.merchantName || "Unknown"} · {when(t.transactedAt)}</span>
                        <button type="button" className="h-9 rounded-lg border border-line px-2 text-xs" aria-label={`Add ${t.merchantName || "payment"} to this trip`}
                          onClick={() => add.mutate(t.id)}>Add · {formatInr(t.amount)}</button>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            ) : (
              <button type="button" className="h-11 w-full rounded-lg border border-line text-sm" onClick={() => setAdding(true)}>Add a payment</button>
            )}
            <button type="button" className="h-11 w-full rounded-lg text-sm text-negative" onClick={() => dismiss.mutate()}>Not a trip</button>
          </>
        )}
      </div>
    </AppShell>
  );
}
