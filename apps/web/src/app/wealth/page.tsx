"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import Section, { Skeleton } from "@/components/console/Section";
import { BlockedList } from "@/components/wealth/NeedsReview";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";
import {
  classLabel, dayLabel, FLOWS_PATH, gainWord, HOLDINGS_PATH, returnText, signedPercent,
  type InvestmentFlows, type WealthHolding, type WealthOverview,
} from "@/lib/wealth";

function Gain({ gain, percent }: { gain: string; percent: string | null }) {
  const word = gainWord(gain);
  const amount = formatInr(gain.replace(/^-/, ""));
  return <>{word === "No change" ? "No change" : `${word} ${amount}`}{percent !== null && word !== "No change" ? ` (${signedPercent(percent)})` : ""}</>;
}

function Holding({ h }: { h: WealthHolding }) {
  return (
    <li>
      <article aria-label={h.scheme} className="rounded-lg bg-page px-3 py-3 text-sm">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="font-medium text-ink">{h.scheme}</p>
            <p className="text-xs text-ink-muted">{h.amc} · Folio {h.folio}{h.plan ? ` · ${h.plan === "direct" ? "Direct" : "Regular"} plan` : ""}</p>
          </div>
          <p className="tabular text-right font-semibold">{formatInr(h.value)}</p>
        </div>
        <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
          <dt className="text-ink-muted">Invested</dt><dd className="tabular text-right">{formatInr(h.invested)}</dd>
          <dt className="text-ink-muted">Gain</dt><dd className="tabular text-right"><Gain gain={h.gain} percent={h.gainPercent} /></dd>
          <dt className="text-ink-muted">Return</dt><dd className="text-right">{returnText(h.ret)}</dd>
          <dt className="text-ink-muted">Price</dt>
          <dd className="text-right">
            {h.valuedAtCost ? "No price yet: valued at cost" : `NAV ₹${h.how.price} on ${dayLabel(h.how.priceDate ?? "")}`}
            {h.how.stale && <span className="ml-1 rounded bg-line px-1">stale</span>}
          </dd>
        </dl>
        {h.issues.length > 0 && <ul className="mt-2 list-disc pl-5 text-xs text-ink">{h.issues.map((i) => <li key={i}>{i}</li>)}</ul>}
        <details className="mt-2">
          <summary className="cursor-pointer text-xs font-medium text-accent">How we got this</summary>
          <ol className="mt-1 list-decimal space-y-1 pl-5 text-xs text-ink-muted">{h.how.steps.map((s) => <li key={s}>{s}</li>)}</ol>
        </details>
      </article>
    </li>
  );
}

function Flows() {
  const q = useQuery({ queryKey: ["wealth", "flows"], queryFn: () => getData<InvestmentFlows>(FLOWS_PATH) });
  return (
    <Section title="Money in and out of investments" error={q.error ? errorMessage(q.error) : null} onRetry={() => q.refetch()}>
      {q.isPending ? <Skeleton lines={2} /> : q.data && (
        <p className="text-sm text-ink">
          In the last 90 days your bank paid {formatInr(q.data.invested)} into investments and received {formatInr(q.data.redeemed)} back.
          These never count as spending or income.
          {q.data.unmatched > 0 && <> {q.data.unmatched} bank payment{q.data.unmatched === 1 ? "" : "s"} didn&apos;t match a statement transaction yet.</>}
        </p>
      )}
    </Section>
  );
}

/**
 * Wealth (AOS-820, D-49, D-79): mutual funds from the owner's statements — total value, invested, gain and return,
 * holdings by asset class with "How we got this", and holdings that don't add up kept apart for review.
 */
export default function WealthPage() {
  const q = useQuery({ queryKey: ["wealth", "holdings"], queryFn: () => getData<WealthOverview>(HOLDINGS_PATH) });
  const o = q.data;
  const empty = o && o.groups.length === 0 && o.needsReview.length === 0;
  return (
    <AppShell>
      <div className="mb-4 flex items-center justify-between gap-2">
        <div>
          <h2 className="text-xl font-bold">Wealth</h2>
          <p className="text-sm text-ink-muted">Your mutual funds, from the statements you import.</p>
        </div>
        <Link href="/wealth/import" className="rounded-lg border border-line px-3 py-2 text-sm">Import a statement</Link>
      </div>
      {q.isPending ? <Skeleton lines={6} /> : q.error ? (
        <p role="alert" className="text-sm text-negative">{errorMessage(q.error)} <button type="button" className="underline" onClick={() => q.refetch()}>Try again</button></p>
      ) : empty ? (
        <p className="rounded-xl border border-line bg-surface p-6 text-center text-sm text-ink-muted">
          No investments yet. <Link href="/wealth/import" className="text-accent underline">Import your CAMS or KFintech statement</Link> to see your mutual funds.
        </p>
      ) : o && (
        <div className="space-y-4">
          <Section title="Your mutual funds">
            <p className="tabular text-2xl font-bold">{formatInr(o.value)}</p>
            <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
              <dt className="text-ink-muted">Invested</dt><dd className="tabular text-right">{formatInr(o.invested)}</dd>
              <dt className="text-ink-muted">Gain</dt><dd className="tabular text-right"><Gain gain={o.gain} percent={o.gainPercent} /></dd>
              <dt className="text-ink-muted">Return</dt><dd className="text-right">{returnText(o.ret)}</dd>
              <dt className="text-ink-muted">Realised so far</dt><dd className="tabular text-right">{formatInr(o.realised)}</dd>
            </dl>
            <p className="mt-2 text-xs text-ink-muted">
              {o.pricesAsOf ? `Prices as of ${dayLabel(o.pricesAsOf)}` : "No prices yet"}
              {o.stale > 0 && ` · ${o.stale} stale`}
              {o.valuedAtCost > 0 && ` · ${o.valuedAtCost} valued at cost`}
            </p>
          </Section>
          {o.needsReview.length > 0 && (
            <Section title={`Needs review (${o.needsReview.length})`}>
              <p className="mb-2 text-xs text-ink-muted">Left out of the totals above until the units add up.</p>
              <BlockedList items={o.needsReview} />
            </Section>
          )}
          {o.groups.map((g) => (
            <Section key={g.assetClass} title={`${classLabel(g.assetClass)} · ${formatInr(g.value)}`}>
              <ul className="space-y-2">{g.holdings.map((h) => <Holding key={`${h.folio}:${h.isin ?? h.scheme}`} h={h} />)}</ul>
            </Section>
          ))}
          <Flows />
        </div>
      )}
    </AppShell>
  );
}
