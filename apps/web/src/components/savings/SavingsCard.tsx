"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Skeleton } from "@/components/console/Section";
import { formatInr } from "@/lib/money";
import { errorMessage, getData } from "@/lib/query";
import { monthName, monthShort, ratePct, savingsPath, trendText, validPercent, type AssumptionInput, type Projection, type SavingsData, type SavingsMonth } from "@/lib/savings";

/**
 * Home's headline (AOS-908, D-80): the savings rate, (income − spent) ÷ income, its trend over 12 months,
 * and what last month's leaks could become if invested monthly, with every assumption shown and editable.
 */
export default function SavingsCard() {
  const [assumptions, setAssumptions] = useState<AssumptionInput | undefined>(undefined);
  const savings = useQuery({ queryKey: ["coach", "savings", assumptions?.annualReturnPct, assumptions?.inflationPct], queryFn: () => getData<SavingsData>(savingsPath(assumptions)) });

  return (
    <section aria-label="Savings rate" className="rounded-xl border border-line bg-surface p-4">
      {savings.isPending ? <Skeleton lines={3} /> : savings.error ? (
        <p role="alert" className="text-sm text-negative">
          {errorMessage(savings.error, "Couldn't load your savings rate")}{" "}
          <button type="button" onClick={() => savings.refetch()} className="font-medium underline">Try again</button>
        </p>
      ) : savings.data.tooLittleData || !savings.data.headline ? (
        <div>
          <h3 className="text-sm font-semibold text-ink">Savings rate</h3>
          <p className="text-sm text-ink-muted">Not enough data yet: the savings rate needs a month with income (salary or other income). Import a statement that includes your salary, and it appears here.</p>
        </div>
      ) : (
        <Headline data={savings.data} headline={savings.data.headline} />
      )}
      {savings.data?.projection && (
        <WhatItCouldBecome p={savings.data.projection} defaults={savings.data.defaults} onApply={setAssumptions} />
      )}
    </section>
  );
}

function Headline({ data, headline }: { data: SavingsData; headline: SavingsMonth }) {
  const trend = trendText(headline, data.previous);
  const rate = headline.rate ?? "0";
  return (
    <div>
      <p className="text-sm text-ink-muted">Savings rate · {monthName(headline.month)}{headline.partial ? " so far" : ""}</p>
      <p className="tabular text-3xl font-semibold text-ink">{ratePct(rate)}</p>
      <p className="tabular text-sm text-ink-muted">
        Saved {formatInr(headline.saved)} of {formatInr(headline.income)} income{trend && <> · {trend}</>}
      </p>
      <RateTrend months={data.months} />
    </div>
  );
}

/** The rate per month as a line (D-49: lines for change over time), with a sentence and a table. Months without income are gaps. */
function RateTrend({ months }: { months: SavingsMonth[] }) {
  const [table, setTable] = useState(false);
  const withRate = months.filter((m) => m.rate !== null);
  if (withRate.length < 2) return null;
  const data = months.map((m) => ({ label: monthShort(m.month), pct: m.rate === null ? null : Math.round(Number(m.rate) * 1000) / 10 }));
  const best = withRate.reduce((a, m) => (Number(m.rate) > Number(a.rate) ? m : a));
  const sentence = `Savings rate by month: best ${monthName(best.month)} (${ratePct(best.rate ?? "0")}); latest ${ratePct(withRate.at(-1)?.rate ?? "0")}.`;
  return (
    <div className="mt-3">
      {table ? (
        <table className="tabular w-full text-sm">
          <caption className="sr-only">Savings rate by month</caption>
          <thead><tr className="text-left text-ink-muted"><th className="font-normal">Month</th><th className="text-right font-normal">Income</th><th className="text-right font-normal">Spent</th><th className="text-right font-normal">Rate</th></tr></thead>
          <tbody>
            {months.map((m) => (
              <tr key={m.month}>
                <td>{monthShort(m.month)}{m.partial ? " (so far)" : ""}</td>
                <td className="text-right">{formatInr(m.income)}</td>
                <td className="text-right">{formatInr(m.spent)}</td>
                <td className="text-right">{m.rate === null ? "no income" : ratePct(m.rate)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div role="img" aria-label={sentence} className="h-32">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid vertical={false} stroke="#f3f4f6" />
              <XAxis dataKey="label" tickLine={false} axisLine={false} fontSize={12} />
              <YAxis tickLine={false} axisLine={false} fontSize={12} width={40} tickFormatter={(v: number) => `${v}%`} />
              <Tooltip formatter={(v) => `${v}%`} />
              <Line type="monotone" dataKey="pct" name="Savings rate" stroke="var(--color-accent)" strokeWidth={2.5} dot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
      <div className="mt-1 text-right text-xs text-ink-muted">
        <button type="button" onClick={() => setTable(!table)} className="underline">{table ? "View as chart" : "View months as table"}</button>
      </div>
    </div>
  );
}

function WhatItCouldBecome({ p, defaults, onApply }: { p: Projection; defaults: AssumptionInput; onApply: (a: AssumptionInput) => void }) {
  const [ret, setRet] = useState(p.annualReturnPct);
  const [inf, setInf] = useState(p.inflationPct);
  const ok = validPercent(ret, 30) && validPercent(inf, 20);
  const submit = (e: FormEvent) => { e.preventDefault(); if (ok) onApply({ annualReturnPct: ret.trim(), inflationPct: inf.trim() }); };
  return (
    <details className="mt-3 border-t border-line pt-3 text-sm">
      <summary className="cursor-pointer font-medium text-ink">What {monthName(p.month).split(" ")[0]}&apos;s leaks could become</summary>
      <p className="mt-2 text-ink-muted">
        You spent <Link href="/leaks" className="underline">{formatInr(p.monthlyAmount)}</Link> on habits in {monthName(p.month)}. Invested every month instead, at {p.annualReturnPct}% a year:
      </p>
      <ul className="tabular mt-1 space-y-1">
        {p.horizons.map((h) => (
          <li key={h.years}>
            In {h.years} years: <span className="font-semibold text-ink">{formatInr(h.futureValue)}</span> ({formatInr(h.inTodaysMoney)} in today&apos;s money at {p.inflationPct}% inflation; you&apos;d have put in {formatInr(h.contributed)})
          </li>
        ))}
      </ul>
      <form onSubmit={submit} className="mt-2 flex flex-wrap items-end gap-2">
        <label className="text-xs text-ink-muted">Return % a year
          <input aria-label="Expected return, percent a year" inputMode="decimal" value={ret} onChange={(e) => setRet(e.target.value)} className="mt-0.5 block h-10 w-24 rounded-lg border border-line px-2 text-sm text-ink" />
        </label>
        <label className="text-xs text-ink-muted">Inflation %
          <input aria-label="Inflation, percent a year" inputMode="decimal" value={inf} onChange={(e) => setInf(e.target.value)} className="mt-0.5 block h-10 w-24 rounded-lg border border-line px-2 text-sm text-ink" />
        </label>
        <button type="submit" disabled={!ok} className="h-10 rounded-lg border border-line px-3 disabled:opacity-40">Recalculate</button>
        <button type="button" onClick={() => { setRet(defaults.annualReturnPct); setInf(defaults.inflationPct); onApply(defaults); }} className="h-10 px-2 text-xs text-ink-muted underline">
          Defaults ({defaults.annualReturnPct}% / {defaults.inflationPct}%)
        </button>
      </form>
      <details className="mt-2 text-xs text-ink-muted">
        <summary className="cursor-pointer text-accent">How we got this</summary>
        <p className="mt-1">{p.formula}. P is the monthly amount, paid at the start of each month. Returns aren&apos;t guaranteed: this shows what the habit costs over time, not advice.</p>
      </details>
    </details>
  );
}
