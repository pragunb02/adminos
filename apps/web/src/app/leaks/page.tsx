"use client";

import Link from "next/link";
import { Suspense, useState, type FormEvent } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import PeriodSwitcher from "@/components/PeriodSwitcher";
import { Skeleton } from "@/components/console/Section";
import UndoToast from "@/components/console/UndoToast";
import LensCard, { type LensActions } from "@/components/leaks/LensCard";
import LimitsPanel from "@/components/limits/LimitsPanel";
import GoalsPanel from "@/components/goals/GoalsPanel";
import { browserZone } from "@/lib/format";
import { activityHref, changeText, leaksPath, LENSES_PATH, lensOverridePath, lensResetPath, readLeakRange, type LeaksData, type LensesData } from "@/lib/leaks";
import { formatInr } from "@/lib/money";
import { parsePeriod, todayIn, type Range } from "@/lib/period";
import { deleteData, errorMessage, getData, putData } from "@/lib/query";


/** "Where it leaks" (AOS-902, D-80): each habit lens, its merchants and its numbers, every number linked to its transactions. */
function LeaksContent() {
  const router = useRouter();
  const pathname = usePathname();
  const search = useSearchParams();
  const today = todayIn(browserZone());
  const { range, custom, notice } = readLeakRange(search, today);
  const { period } = parsePeriod(search, today);
  const queryClient = useQueryClient();

  const leaks = useQuery({ queryKey: ["coach", "leaks", range.from, range.to], queryFn: () => getData<LeaksData>(leaksPath(range)) });
  const lenses = useQuery({ queryKey: ["coach", "lenses"], queryFn: () => getData<LensesData>(LENSES_PATH) });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["coach"] });
  const setOverride = useMutation({
    mutationFn: (v: { lens: string; merchant: string; include: boolean }) => putOverride(v.lens, v.merchant, v.include),
    onSuccess: refresh,
  });
  const clearOverride = useMutation({
    mutationFn: (v: { lens: string; merchant: string }) => deleteData(lensResetPath(v.lens, v.merchant)),
    onSuccess: refresh,
  });
  const [toast, setToast] = useState<{ id: string; message: string; undo: () => void } | null>(null);
  const labelOf = (id: string) => leaks.data?.lenses.find((l) => l.id === id)?.label ?? id;
  const actions: LensActions = {
    busy: setOverride.isPending || clearOverride.isPending,
    exclude: (lens, merchant) => setOverride.mutate({ lens, merchant, include: false }, {
      onSuccess: () => setToast({
        id: `${lens}:${merchant}`,
        message: `${merchant} is no longer counted in ${labelOf(lens)}`,
        undo: () => clearOverride.mutate({ lens, merchant }, { onSuccess: () => setToast(null) }),
      }),
    }),
    include: (lens, merchant) => setOverride.mutate({ lens, merchant, include: true }),
    reset: (lens, merchant) => clearOverride.mutate({ lens, merchant }),
  };
  const mutationError = setOverride.error ?? clearOverride.error;

  const showRange = (r: Range | null) => {
    const q = new URLSearchParams(search);
    q.delete("from"); q.delete("to");
    if (r) { q.set("from", r.from); q.set("to", r.to); q.delete("p"); q.delete("d"); }
    const s = q.toString();
    router.push(`${pathname}${s ? `?${s}` : ""}`, { scroll: false });
  };

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold">Where it leaks</h2>
      {custom ? (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-lg font-semibold" aria-live="polite">{range.from} → {range.to}</p>
          <button type="button" onClick={() => showRange(null)} className="h-11 rounded-lg border border-line bg-surface px-3 text-sm">Back to periods</button>
        </div>
      ) : (
        <PeriodSwitcher period={period} today={today} />
      )}
      <CustomRange key={`${range.from}:${range.to}`} range={range} today={today} onApply={showRange} />
      {notice && <p role="status" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">{notice}</p>}
      {mutationError && <p role="alert" className="text-sm text-negative">{errorMessage(mutationError, "Couldn't change the lens")}</p>}

      {leaks.isPending ? (
        <Skeleton lines={6} />
      ) : leaks.error ? (
        <p role="alert" className="text-sm text-negative">
          {errorMessage(leaks.error, "Couldn't load the leaks")}{" "}
          <button type="button" onClick={() => leaks.refetch()} className="font-medium underline">Try again</button>
        </p>
      ) : (
        <>
          <TotalLine data={leaks.data} />
          <LimitsPanel />
          <GoalsPanel />
          {leaks.data.total.orders === 0 && leaks.data.total.spent === "0.00" && (
            <p className="rounded-xl border border-line bg-surface p-4 text-sm text-ink-muted">
              No leaks in this period: nothing from quick commerce, food delivery, nights out, shopping, pay-later or travel.
            </p>
          )}
          <div className="grid gap-4 lg:grid-cols-2">
            {leaks.data.lenses.map((l) => (
              <LensCard key={l.id} leak={l} range={range} previous={leaks.data.previousPeriod} lateFrom={leaks.data.lateNight.from}
                definition={lenses.data?.lenses.find((d) => d.id === l.id)} actions={actions} />
            ))}
          </div>
          <HowWeGotThis lateNight={leaks.data.lateNight} />
        </>
      )}
      {toast && <UndoToast key={toast.id} message={toast.message} onUndo={toast.undo} onClose={() => setToast(null)} />}
    </div>
  );
}

const putOverride = (lens: string, merchant: string, include: boolean) => putData<unknown>(lensOverridePath(lens), { merchant, include });

function TotalLine({ data }: { data: LeaksData }) {
  const change = changeText(data.total.changePct);
  return (
    <p className="tabular text-sm text-ink-muted" aria-live="polite">
      All leaks{" "}
      <Link href={activityHref(data.period)} className="font-semibold text-ink underline decoration-line underline-offset-2" aria-label={`All leaks: ${formatInr(data.total.spent)}, see transactions`}>
        {formatInr(data.total.spent)}
      </Link>
      {" "}over {data.total.orders} {data.total.orders === 1 ? "order" : "orders"}
      {change && <> · {change}</>}
    </p>
  );
}

function CustomRange({ range, today, onApply }: { range: Range; today: string; onApply: (r: Range) => void }) {
  const [draft, setDraft] = useState(range);
  const submit = (e: FormEvent) => { e.preventDefault(); onApply(draft); };
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-xs text-accent">Custom range</summary>
      <form onSubmit={submit} className="mt-2 flex flex-wrap items-end gap-2">
        <input type="date" aria-label="From date" value={draft.from} max={today} onChange={(e) => setDraft({ ...draft, from: e.target.value })} className="h-11 rounded-lg border border-line bg-surface px-2" />
        <input type="date" aria-label="To date" value={draft.to} max={today} onChange={(e) => setDraft({ ...draft, to: e.target.value })} className="h-11 rounded-lg border border-line bg-surface px-2" />
        <button type="submit" disabled={!draft.from || !draft.to} className="h-11 rounded-lg bg-accent px-4 font-semibold text-white disabled:opacity-40">Show</button>
      </form>
    </details>
  );
}

function HowWeGotThis({ lateNight }: { lateNight: { from: string; to: string } }) {
  return (
    <details className="rounded-xl border border-line bg-surface p-4 text-sm">
      <summary className="cursor-pointer font-semibold">How we got this</summary>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-ink-muted">
        <li>A lens is a habit: a list of apps and shops, categories and name keywords. Edit any lens to add or take out a merchant.</li>
        <li>Spent is purchases minus refunds in the period, so a refund for an earlier order can make a lens negative.</li>
        <li>Orders are purchases; the average order is their total before refunds divided by their number.</li>
        <li>Late night is {lateNight.from}–{lateNight.to} in your time zone. Statement imports have no time, so they aren&apos;t counted either way.</li>
        <li>&quot;Before&quot; is the same number of days just before this period.</li>
        <li>UPI payments to people (like settling a split bill) never count, and neither do transfers between your own accounts.</li>
        <li>All leaks counts each payment once, even when it is in two lenses.</li>
      </ul>
    </details>
  );
}

export default function LeaksPage() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={4} />}>
        <LeaksContent />
      </Suspense>
    </AppShell>
  );
}
