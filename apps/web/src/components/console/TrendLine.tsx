"use client";

import { useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { compactInr } from "@/lib/format";
import { formatInr, toPaise } from "@/lib/money";
import type { SeriesPoint } from "@/lib/analytics";

const MONTH = new Intl.DateTimeFormat("en-IN", { month: "short", timeZone: "UTC" });
const WEEK = new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short", timeZone: "UTC" });

/** Spending over months or weeks as a line (D-49: lines for change over time), with a sentence and a table. */
export default function TrendLine({ points, bucket, title }: { points: SeriesPoint[]; bucket: "month" | "week"; title: string }) {
  const [table, setTable] = useState(false);
  const label = (start: string) => (bucket === "month" ? MONTH : WEEK).format(new Date(`${start}T00:00:00Z`));
  const data = points.map((p) => ({ label: label(p.start), rupees: Number(toPaise(p.spent)) / 100, spent: p.spent }));
  const high = points.reduce((m, p) => (toPaise(p.spent) > toPaise(m.spent) ? p : m), points[0] ?? { start: "", spent: "0", received: "0" });
  const latest = points[points.length - 1];
  const sentence = points.length
    ? `${title}: highest in ${label(high.start)} (${formatInr(high.spent)}); latest ${label(latest.start)} ${formatInr(latest.spent)}.`
    : `${title}: nothing yet.`;
  return (
    <div>
      {table ? (
        <table className="tabular w-full text-sm">
          <caption className="sr-only">{title}</caption>
          <thead><tr className="text-left text-ink-muted"><th className="font-normal">{bucket === "month" ? "Month" : "Week of"}</th><th className="text-right font-normal">Spent</th></tr></thead>
          <tbody>{points.map((p) => <tr key={p.start}><td>{label(p.start)}</td><td className="text-right">{formatInr(p.spent)}</td></tr>)}</tbody>
        </table>
      ) : (
        <div role="img" aria-label={sentence} className="h-44">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid vertical={false} stroke="#f3f4f6" />
              <XAxis dataKey="label" tickLine={false} axisLine={false} fontSize={12} />
              <YAxis tickLine={false} axisLine={false} fontSize={12} width={56} tickFormatter={(v: number) => compactInr(v.toFixed(2))} />
              <Tooltip formatter={(_, __, item) => formatInr((item.payload as { spent: string }).spent)} />
              <Line type="monotone" dataKey="rupees" name="Spent" stroke="var(--color-accent)" strokeWidth={2.5} dot={{ r: 3 }} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
      <div className="mt-2 text-right text-xs text-ink-muted">
        <button type="button" onClick={() => setTable(!table)} className="underline">{table ? "View as chart" : "View as table"}</button>
      </div>
    </div>
  );
}
