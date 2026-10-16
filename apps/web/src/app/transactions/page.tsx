"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import { Skeleton } from "@/components/console/Section";
import { FeedRowContent } from "@/components/console/FeedRow";
import UndoToast from "@/components/console/UndoToast";
import { categoryLabel } from "@/lib/analytics";
import { CATEGORIES } from "@/lib/consolePeriod";
import { browserZone, relativeDate } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { parsePeriod, rangeOf, todayIn } from "@/lib/period";
import { errorMessage, getPage, patchData } from "@/lib/query";
import type { Transaction } from "@/lib/types";

/** What a transaction counts as (D-39), offered as a filter. */
const KINDS = [
  { value: "spend", label: "Spending" },
  { value: "income", label: "Income" },
  { value: "refund", label: "Refunds" },
  { value: "transfer", label: "Transfers" },
  { value: "reversal", label: "Reversals" },
] as const;

interface Filters { q: string; category: string; nature: string; from: string; to: string }
const FILTER_KEYS = ["q", "category", "nature", "from", "to"] as const;

/** Filters come from the URL (D-47). A Home/Spending period (`p`/`d`) becomes a date range. */
function readFilters(search: URLSearchParams, today: string): Filters {
  const get = (k: string) => search.get(k) ?? "";
  let from = get("from");
  let to = get("to");
  if (!from && !to && search.get("p")) ({ from, to } = rangeOf(parsePeriod(search, today).period, today));
  const category = (CATEGORIES as readonly string[]).includes(get("category")) ? get("category") : "";
  const nature = KINDS.some((k) => k.value === get("nature")) ? get("nature") : "";
  return { q: get("q"), category, nature, from, to };
}

function query(filters: Filters, extra: Record<string, string> = {}) {
  const q = new URLSearchParams();
  for (const k of FILTER_KEYS) if (filters[k]) q.set(k, filters[k]);
  for (const [k, v] of Object.entries(extra)) q.set(k, v);
  return q;
}

/** Activity (AOS-307, M/Activity): every transaction, searchable and filterable, editable in place. */
function ActivityContent() {
  const router = useRouter();
  const pathname = usePathname();
  const search = useSearchParams();
  const zone = browserZone();
  const today = todayIn(zone);
  const filters = readFilters(search, today);
  const openId = search.get("txn");
  const queryClient = useQueryClient();

  // Search box: typing replaces the URL (no history flood); the request waits 300 ms.
  const [typed, setTyped] = useState(filters.q);
  useEffect(() => {
    if (typed === filters.q) return;
    const t = setTimeout(() => router.replace(`${pathname}${qs(query({ ...filters, q: typed }))}`, { scroll: false }), 300);
    return () => clearTimeout(t);
  }, [typed, filters, pathname, router]);

  const apiQuery = query({ ...filters }, { limit: "30" });
  apiQuery.delete("q");
  if (filters.q) apiQuery.set("merchant", filters.q);
  const feed = useInfiniteQuery({
    queryKey: ["transactions", "activity", apiQuery.toString()],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => {
      const q = new URLSearchParams(apiQuery);
      if (pageParam) q.set("cursor", pageParam);
      return getPage<Transaction>(`/api/v1/transactions?${q}`);
    },
    getNextPageParam: (last) => (last.hasMore ? last.cursor : null),
  });
  const rows = useMemo(() => feed.data?.pages.flatMap((p) => p.items) ?? [], [feed.data]);
  const groups = useMemo(() => {
    const out: { day: string; items: Transaction[] }[] = [];
    for (const t of rows) {
      const day = relativeDate(t.transactedAt, zone);
      if (out.at(-1)?.day === day) out.at(-1)!.items.push(t);
      else out.push({ day, items: [t] });
    }
    return out;
  }, [rows, zone]);

  const push = (f: Filters, extra: Record<string, string> = {}) => router.push(`${pathname}${qs(query(f, extra))}`, { scroll: false });
  const open = (t: Transaction) => push(filters, { txn: t.id });
  const close = () => router.replace(`${pathname}${qs(query(filters))}`, { scroll: false });
  const selected = openId ? rows.find((t) => t.id === openId) ?? null : null;

  // Re-categorize with undo (D-41: remembered for the payee unless turned off).
  const [toast, setToast] = useState<{ message: string; undo: () => void } | null>(null);
  const recategorize = useMutation({
    mutationFn: (v: { id: string; category: string; remember: boolean }) => patchData<Transaction>(`/api/v1/transactions/${v.id}`, { category: v.category, remember: v.remember }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["transactions"] });
      queryClient.invalidateQueries({ queryKey: ["analytics"] });
    },
  });
  const save = (t: Transaction, category: string, remember: boolean) =>
    recategorize.mutate({ id: t.id, category, remember }, {
      onSuccess: () => {
        close();
        setToast({
          message: `${categoryLabel(t.category)} → ${categoryLabel(category)}${remember ? ", remembered" : ""}`,
          undo: () => recategorize.mutate({ id: t.id, category: t.category, remember }, { onSuccess: () => setToast(null) }),
        });
      },
    });

  const active = [
    filters.category && { key: "category", label: categoryLabel(filters.category) },
    filters.nature && { key: "nature", label: KINDS.find((k) => k.value === filters.nature)!.label },
    (filters.from || filters.to) && { key: "dates", label: `${filters.from || "…"} → ${filters.to || "…"}` },
  ].filter(Boolean) as { key: string; label: string }[];

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-bold">Transactions</h2>
      <input
        type="search"
        aria-label="Search merchant or note"
        placeholder="Search merchant or note"
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        className="h-11 w-full rounded-lg border border-line bg-surface px-3 text-sm"
      />
      {/* Re-created when the URL changes, so its draft always starts from the applied filters. */}
      <FilterBar key={query(filters).toString()} filters={filters} onApply={(f) => push(f)} />
      {active.length > 0 && (
        <ul aria-label="Active filters" className="flex flex-wrap gap-2">
          {active.map((a) => (
            <li key={a.key}>
              <button
                type="button"
                aria-label={`Remove filter ${a.label}`}
                onClick={() => push(a.key === "dates" ? { ...filters, from: "", to: "" } : { ...filters, [a.key]: "" })}
                className="min-h-8 rounded-full bg-blue-50 px-3 py-1 text-xs text-accent"
              >
                {a.label} ×
              </button>
            </li>
          ))}
        </ul>
      )}

      {feed.isPending ? (
        <Skeleton lines={6} />
      ) : feed.error ? (
        <p role="alert" className="text-sm text-negative">{errorMessage(feed.error, "Failed to load transactions")} <button type="button" className="underline" onClick={() => feed.refetch()}>Try again</button></p>
      ) : rows.length === 0 ? (
        <p className="py-8 text-center text-sm text-ink-muted">{active.length || filters.q ? "No transactions match these filters." : "No transactions yet."}</p>
      ) : (
        <div className="space-y-4">
          {groups.map((g) => (
            <section key={g.day} aria-label={g.day}>
              <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-muted">{g.day}</h3>
              <ul className="divide-y divide-line rounded-xl border border-line bg-surface px-3">
                {g.items.map((t) => (
                  <li key={t.id}>
                    <button type="button" onClick={() => open(t)} className="block w-full text-left" aria-label={`Edit ${t.merchantName || "Unknown"} ${formatInr(t.amount)}`}>
                      <FeedRowContent txn={t} zone={zone} />
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
          {feed.hasNextPage && (
            <button type="button" onClick={() => feed.fetchNextPage()} disabled={feed.isFetchingNextPage} className="h-11 w-full rounded-lg border border-line bg-surface text-sm text-accent disabled:opacity-50">
              {feed.isFetchingNextPage ? "Loading…" : "Load more"}
            </button>
          )}
        </div>
      )}

      {openId && !feed.isPending && <TransactionSheet key={selected?.id ?? "missing"} txn={selected} zone={zone} saving={recategorize.isPending} error={recategorize.error ? errorMessage(recategorize.error) : null} onSave={save} onClose={close} />}
      {toast && <UndoToast message={toast.message} onUndo={toast.undo} onClose={() => setToast(null)} />}
    </div>
  );
}

const qs = (q: URLSearchParams) => (q.toString() ? `?${q}` : "");

/** Category / kind / dates, applied together (NN/g: batch filters on mobile; nothing jumps while choosing). */
function FilterBar({ filters, onApply }: { filters: Filters; onApply: (f: Filters) => void }) {
  const [draft, setDraft] = useState(filters);
  const [open, setOpen] = useState(false);
  const set = (k: keyof Filters) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setDraft({ ...draft, [k]: e.target.value });
  const apply = () => { onApply({ ...draft, q: filters.q }); setOpen(false); };
  const fields = (
    <div className="grid gap-2 sm:grid-cols-4">
      <select aria-label="Category" value={draft.category} onChange={set("category")} className="h-11 rounded-lg border border-line bg-surface px-2 text-sm">
        <option value="">All categories</option>
        {CATEGORIES.map((c) => <option key={c} value={c}>{categoryLabel(c)}</option>)}
      </select>
      <select aria-label="Kind" value={draft.nature} onChange={set("nature")} className="h-11 rounded-lg border border-line bg-surface px-2 text-sm">
        <option value="">Everything</option>
        {KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}
      </select>
      <input type="date" aria-label="From date" value={draft.from} onChange={set("from")} className="h-11 rounded-lg border border-line bg-surface px-2 text-sm" />
      <input type="date" aria-label="To date" value={draft.to} onChange={set("to")} className="h-11 rounded-lg border border-line bg-surface px-2 text-sm" />
    </div>
  );
  return (
    <>
      <div className="hidden items-end gap-2 lg:flex">
        <div className="flex-1">{fields}</div>
        <button type="button" onClick={apply} className="h-11 rounded-lg bg-accent px-4 text-sm font-semibold text-white">Apply</button>
      </div>
      <div className="lg:hidden">
        <button type="button" onClick={() => { setDraft(filters); setOpen(true); }} className="h-11 rounded-lg border border-line bg-surface px-4 text-sm">Filters</button>
        {open && (
          <div className="fixed inset-0 z-50 flex items-end bg-black/40" onClick={() => setOpen(false)}>
            <div role="dialog" aria-modal="true" aria-label="Filters" className="w-full space-y-3 rounded-t-2xl bg-surface p-4" onClick={(e) => e.stopPropagation()}>
              {fields}
              <div className="flex gap-2">
                <button type="button" onClick={() => setOpen(false)} className="h-11 flex-1 rounded-lg border border-line text-sm">Cancel</button>
                <button type="button" onClick={apply} className="h-11 flex-1 rounded-lg bg-accent text-sm font-semibold text-white">Apply</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </>
  );
}

/** One transaction: details and re-categorize (URL `?txn=id`, so Back closes it). */
function TransactionSheet({ txn, zone, saving, error, onSave, onClose }: {
  txn: Transaction | null; zone: string; saving: boolean; error: string | null;
  onSave: (t: Transaction, category: string, remember: boolean) => void; onClose: () => void;
}) {
  const [category, setCategory] = useState(txn?.category ?? "other");
  const [remember, setRemember] = useState(true);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 sm:items-center" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-label="Transaction" className="w-full max-w-md space-y-3 rounded-t-2xl bg-surface p-5 sm:rounded-2xl" onClick={(e) => e.stopPropagation()}>
        {!txn ? (
          <>
            <p className="text-sm text-ink-muted">This transaction isn&apos;t in the current list.</p>
            <button type="button" onClick={onClose} className="h-11 w-full rounded-lg border border-line text-sm">Close</button>
          </>
        ) : (
          <>
            <div>
              <p className="text-sm text-ink-muted">{txn.merchantName || "Unknown"}</p>
              <p className="tabular text-2xl font-semibold">{txn.type === "credit" ? "+" : "−"}{formatInr(txn.amount, { paise: true })}</p>
              <p className="text-xs text-ink-muted">
                {new Intl.DateTimeFormat("en-IN", { timeZone: zone, dateStyle: "medium", timeStyle: "short" }).format(new Date(txn.transactedAt))}
                {txn.accountLast4 && ` · ··${txn.accountLast4}`} · from {txn.sourceType}
              </p>
            </div>
            <label className="block text-sm">
              <span className="mb-1 block text-ink-muted">Category</span>
              <select aria-label="Category for this transaction" value={category} onChange={(e) => setCategory(e.target.value)} className="h-11 w-full rounded-lg border border-line px-2">
                {CATEGORIES.map((c) => <option key={c} value={c}>{categoryLabel(c)}</option>)}
              </select>
            </label>
            <label className="flex min-h-11 items-center gap-2 text-sm">
              <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} className="h-5 w-5" />
              Remember for {txn.merchantName || "this payee"} (past and future)
            </label>
            {error && <p role="alert" className="text-sm text-negative">{error}</p>}
            <div className="flex gap-2">
              <button type="button" onClick={onClose} className="h-11 flex-1 rounded-lg border border-line text-sm">Close</button>
              <button type="button" disabled={saving || category === txn.category} onClick={() => onSave(txn, category, remember)} className="h-11 flex-1 rounded-lg bg-accent text-sm font-semibold text-white disabled:opacity-40">
                {saving ? "Saving…" : "Save"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

export default function TransactionsPage() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={6} />}>
        <ActivityContent />
      </Suspense>
    </AppShell>
  );
}
