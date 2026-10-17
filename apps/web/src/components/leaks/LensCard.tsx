"use client";

import Link from "next/link";
import { useState, type FormEvent, type ReactNode } from "react";
import { formatInr } from "@/lib/money";
import { activityHref, changeText, percent, ruleSummary, type LensDefinition, type LensLeak, type LeakNumbers } from "@/lib/leaks";
import type { Range } from "@/lib/period";
import WeekdayBars from "./WeekdayBars";

export interface LensActions {
  /** Take a merchant out of this lens ("Not a leak"). */
  exclude: (lens: string, merchant: string) => void;
  /** Add a merchant to this lens. */
  include: (lens: string, merchant: string) => void;
  /** Back to the lens's own rules for this merchant. */
  reset: (lens: string, merchant: string) => void;
  busy: boolean;
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** "2 of 3 orders after 22:00 (67%)", or why there's no share. */
function lateNightText(n: LeakNumbers, from: string): string {
  if (n.lateNight.share === null) return n.orders === 0 ? "No orders" : "No order times (statement rows)";
  return `${n.lateNight.orders} of ${plural(n.lateNight.timedOrders, "order")} after ${from} (${percent(n.lateNight.share)})`;
}

/** One habit lens on "Where it leaks" (AOS-902): its numbers, its weekdays and its merchants; every number links to its transactions. */
export default function LensCard({ leak, range, previous, lateFrom, definition, actions }: {
  leak: LensLeak; range: Range; previous: Range; lateFrom: string; definition?: LensDefinition; actions: LensActions;
}) {
  const n = leak.numbers;
  const change = changeText(n.changePct);
  const refundsOnly = n.spent.startsWith("-");
  const link = (children: ReactNode, href: string, label: string) => <Link href={href} aria-label={label} className="underline decoration-line underline-offset-2 hover:decoration-accent">{children}</Link>;
  return (
    <section aria-label={leak.label} className="rounded-xl border border-line bg-surface p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-ink">{leak.label}</h3>
          <p className="text-xs text-ink-muted">{leak.description}</p>
        </div>
        <p className="tabular text-lg font-semibold text-ink">
          {link(formatInr(n.spent), activityHref(range, { lens: leak.id }), `${leak.label}: ${formatInr(n.spent)} spent, see transactions`)}
          {refundsOnly && <span className="ml-1 text-xs font-normal text-ink-muted">(refunds only)</span>}
        </p>
      </div>

      <dl className="tabular mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-4">
        <div><dt className="text-xs text-ink-muted">Orders</dt><dd>{link(n.orders, activityHref(range, { lens: leak.id }), `${leak.label}: ${plural(n.orders, "order")}, see transactions`)}</dd></div>
        <div><dt className="text-xs text-ink-muted">Average order</dt><dd>{n.averageOrder ? formatInr(n.averageOrder) : "—"}</dd></div>
        <div className="col-span-2">
          <dt className="text-xs text-ink-muted">Late night</dt>
          <dd>{n.lateNight.orders > 0 ? link(lateNightText(n, lateFrom), activityHref(range, { lens: leak.id, late: true }), `${leak.label}: late-night orders, see transactions`) : lateNightText(n, lateFrom)}</dd>
        </div>
      </dl>
      <p className="tabular mt-1 text-xs text-ink-muted">
        Before: {link(formatInr(n.previousSpent), activityHref(previous, { lens: leak.id }), `${leak.label}: ${formatInr(n.previousSpent)} the period before, see transactions`)}
        {change && <> · {change}</>}
      </p>

      {n.orders > 0 && (
        <div className="mt-3">
          <WeekdayBars days={n.weekdays} title={`${leak.label} by weekday`} hrefFor={(d) => activityHref(range, { lens: leak.id, weekday: d })} />
        </div>
      )}

      {leak.merchants.length > 0 ? (
        <ul className="mt-3 divide-y divide-line" aria-label={`${leak.label} merchants`}>
          {leak.merchants.map((m) => (
            <li key={m.merchant} className="flex items-center justify-between gap-3 py-2 text-sm">
              <span className="min-w-0">
                <Link href={activityHref(range, { lens: leak.id, merchant: m.merchant })} className="block truncate text-ink underline decoration-line underline-offset-2">{m.merchant}</Link>
                <span className="tabular text-xs text-ink-muted">
                  {plural(m.numbers.orders, "order")}
                  {m.numbers.averageOrder && m.numbers.orders > 1 && ` · ${formatInr(m.numbers.averageOrder)} each`}
                  {m.numbers.lateNight.orders > 0 && ` · ${m.numbers.lateNight.orders} late`}
                </span>
              </span>
              <span className="flex items-center gap-2">
                <span className="tabular text-ink">{formatInr(m.numbers.spent)}</span>
                <button type="button" disabled={actions.busy} onClick={() => actions.exclude(leak.id, m.merchant)}
                  aria-label={`Not a leak: take ${m.merchant} out of ${leak.label}`}
                  className="min-h-8 rounded-md border border-line px-2 text-xs text-ink-muted hover:bg-page disabled:opacity-40">
                  Not a leak
                </button>
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-3 text-sm text-ink-muted">Nothing in this period.</p>
      )}

      <LensEditor lens={leak} definition={definition} actions={actions} />
    </section>
  );
}

/** "Edit" for one lens: the owner's additions and removals, and adding a merchant by name. */
function LensEditor({ lens, definition, actions }: { lens: LensLeak; definition?: LensDefinition; actions: LensActions }) {
  const [name, setName] = useState("");
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const merchant = name.trim();
    if (!merchant) return;
    actions.include(lens.id, merchant);
    setName("");
  };
  const added = definition?.added ?? [];
  const removed = definition?.removed ?? [];
  return (
    <details className="mt-3 text-sm">
      <summary className="cursor-pointer text-xs text-accent">Edit {lens.label}</summary>
      <div className="mt-2 space-y-2">
        {definition && (
          <p className="text-xs text-ink-muted">
            Counts {ruleSummary(definition)}.
            Payments to people by UPI never count.
          </p>
        )}
        {added.length > 0 && <OverrideList title="You added" names={added} lens={lens} actions={actions} verb="Remove" />}
        {removed.length > 0 && <OverrideList title="You took out" names={removed} lens={lens} actions={actions} verb="Put back" />}
        <form onSubmit={submit} className="flex gap-2">
          <input aria-label={`Merchant to add to ${lens.label}`} value={name} maxLength={255} onChange={(e) => setName(e.target.value)}
            placeholder="Merchant name, as in Activity" className="h-10 min-w-0 flex-1 rounded-lg border border-line bg-surface px-2 text-sm" />
          <button type="submit" disabled={actions.busy || !name.trim()} className="h-10 rounded-lg bg-accent px-3 text-sm font-semibold text-white disabled:opacity-40">Add</button>
        </form>
      </div>
    </details>
  );
}

function OverrideList({ title, names, lens, actions, verb }: { title: string; names: string[]; lens: LensLeak; actions: LensActions; verb: string }) {
  return (
    <div>
      <p className="text-xs text-ink-muted">{title}</p>
      <ul className="flex flex-wrap gap-2">
        {names.map((m) => (
          <li key={m}>
            <button type="button" disabled={actions.busy} onClick={() => actions.reset(lens.id, m)} aria-label={`${verb} ${m} (${lens.label})`}
              className="min-h-8 rounded-full bg-blue-50 px-3 py-1 text-xs text-accent disabled:opacity-40">
              {m} · {verb}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
