"use client";

import { Suspense } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import { Skeleton } from "@/components/console/Section";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";
import { modesLabel, tripDates } from "@/lib/trips";
import type { TripYear } from "@/lib/types";

const YEAR = /^\d{4}$/;

/**
 * Trips (AOS-910, D-90): travel bookings and the spends around them, each with one total, and the
 * year's total. The year is in the URL (D-47); without one, the API's current year is shown.
 */
function TripsContent() {
  const router = useRouter();
  const pathname = usePathname();
  const raw = useSearchParams().get("year");
  const year = raw && YEAR.test(raw) ? raw : null;
  const q = useQuery({ queryKey: ["trips", year], queryFn: () => getData<TripYear>(`/api/v1/trips${year ? `?year=${year}` : ""}`) });
  const go = (y: number) => router.push(`${pathname}?year=${y}`, { scroll: false });

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold">Trips</h2>
      {q.isPending ? (
        <Skeleton lines={4} />
      ) : q.error ? (
        <p role="alert" className="text-sm text-negative">{errorMessage(q.error)} <button type="button" className="underline" onClick={() => q.refetch()}>Try again</button></p>
      ) : (
        <>
          <div className="flex items-center justify-between gap-2">
            <button type="button" className="h-11 rounded-lg border border-line px-3 text-sm" onClick={() => go(q.data.year - 1)} aria-label={`Trips in ${q.data.year - 1}`}>‹</button>
            <p className="text-center">
              <span className="block text-sm text-ink-muted">Trips in {q.data.year}</span>
              <span className="tabular text-2xl font-bold">{formatInr(q.data.total)}</span>
            </p>
            <button type="button" className="h-11 rounded-lg border border-line px-3 text-sm" onClick={() => go(q.data.year + 1)} aria-label={`Trips in ${q.data.year + 1}`}>›</button>
          </div>
          {q.data.trips.length === 0 ? (
            <p className="rounded-xl border border-line bg-surface p-6 text-center text-sm text-ink-muted">
              No trips in {q.data.year}. Flights, trains and buses you book show up here with the cabs and food around them.
            </p>
          ) : (
            <ul aria-label={`Trips in ${q.data.year}`} className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
              {q.data.trips.map((t) => (
                <li key={t.id}>
                  <Link href={`/trips/${t.id}`} className="flex items-center justify-between gap-3 px-4 py-3 hover:bg-page">
                    <span className="min-w-0">
                      <span className="block truncate text-sm font-medium">{t.name}</span>
                      <span className="block text-xs text-ink-muted">{tripDates(t.start, t.end)} · {modesLabel(t.modes)} · {t.transactionCount} payments</span>
                    </span>
                    <span className="tabular text-sm font-semibold">{formatInr(t.total)}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}

export default function TripsPage() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={4} />}>
        <TripsContent />
      </Suspense>
    </AppShell>
  );
}
