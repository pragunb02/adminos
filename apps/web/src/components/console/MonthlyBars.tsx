"use client";

import { useState } from "react";
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { compactInr } from "@/lib/format";
import { formatInr, toPaise } from "@/lib/money";
import type { SeriesPoint } from "@/lib/analytics";

const MONTH = new Intl.DateTimeFormat("en-IN", { month: "short", timeZone: "UTC" });
const MONTH_YEAR = new Intl.DateTimeFormat("en-IN", { month: "short", year: "numeric", timeZone: "UTC" });

/**
 * Spent vs received per month (M/Spending "Trends"): paired bars or lines (D-49 — no stacking).
 * The current month is "so far". One sentence and an exact table, as everywhere on the console.
 */
export default function MonthlyBars({ points, kind, currentMonth }: { points: SeriesPoint[]; kind: "bars" | "lines"; currentMonth: string | null }) {
  const [table, setTable] = useState(false);
  const label = (s: string) => `${MONTH.format(new Date(`${s}T00:00:00Z`))}${s === currentMonth ? " (so far)" : ""}`;
  const data = points.map((p) => ({ month: MONTH.format(new Date(`${p.start}T00:00:00Z`)), spent: Number(toPaise(p.spent)) / 100, received: Number(toPaise(p.received)) / 100, raw: p }));
  const high = points.reduce((m, p) => (toPaise(p.spent) > toPaise(m.spent) ? p : m), points[0] ?? { start: "", spent: "0", received: "0" });
  const latest = points[points.length - 1];
  const sentence = points.length
    ? `Last ${points.length} months: most spent in ${MONTH_YEAR.format(new Date(`${high.start}T00:00:00Z`))} (${formatInr(high.spent)}); ${label(latest.start)} spent ${formatInr(latest.spent)}, received ${formatInr(latest.received)}.`
    : "Last 12 months: nothing yet.";
  const axes = (
    <>
      <CartesianGrid vertical={false} stroke="#f3f4f6" />
      <XAxis dataKey="month" tickLine={false} axisLine={false} fontSize={12} />
      <YAxis tickLine={false} axisLine={false} fontSize={12} width={56} tickFormatter={(v: number) => compactInr(v.toFixed(2))} />
      <Tooltip formatter={(v) => (typeof v === "number" ? formatInr(v.toFixed(2)) : "—")} />
    </>
  );
  return (
    <div>
      {table ? (
        <table className="tabular w-full text-sm">
          <caption className="sr-only">Spent and received by month</caption>
          <thead><tr className="text-left text-ink-muted"><th className="font-normal">Month</th><th className="text-right font-normal">Spent</th><th className="text-right font-normal">Received</th></tr></thead>
          <tbody>{points.map((p) => <tr key={p.start}><td>{label(p.start)}</td><td className="text-right">{formatInr(p.spent)}</td><td className="text-right">{formatInr(p.received)}</td></tr>)}</tbody>
        </table>
      ) : (
        <div role="img" aria-label={sentence} className="h-56">
          <ResponsiveContainer width="100%" height="100%">
            {kind === "bars" ? (
              <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                {axes}
                <Bar dataKey="spent" name="Spent" fill="var(--color-accent)" radius={[3, 3, 0, 0]} isAnimationActive={false} />
                <Bar dataKey="received" name="Received" fill="var(--color-compare)" radius={[3, 3, 0, 0]} isAnimationActive={false} />
              </BarChart>
            ) : (
              <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                {axes}
                <Line dataKey="spent" name="Spent" stroke="var(--color-accent)" strokeWidth={2.5} dot={{ r: 3 }} isAnimationActive={false} />
                <Line dataKey="received" name="Received" stroke="var(--color-compare)" strokeDasharray="5 4" dot={{ r: 3 }} isAnimationActive={false} />
              </LineChart>
            )}
          </ResponsiveContainer>
        </div>
      )}
      <div className="mt-2 flex items-center justify-between text-xs text-ink-muted">
        <span>
          <span aria-hidden className="mr-1 inline-block h-2 w-3 rounded-sm bg-accent align-middle" />Spent
          <span aria-hidden className="ml-3 mr-1 inline-block h-2 w-3 rounded-sm bg-compare align-middle" />Received
        </span>
        <button type="button" onClick={() => setTable(!table)} className="underline">{table ? "View as chart" : "View as table"}</button>
      </div>
    </div>
  );
}
