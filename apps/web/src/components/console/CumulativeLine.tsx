"use client";

import { useState } from "react";
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { compactInr } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { mergeForChart } from "@/lib/cumulative";

/**
 * Running total this month (solid, accent) vs last month (dashed, grey) by day of month (M/Home).
 * Numbers are drawn from exact strings; the axis uses ₹K/L/Cr. The image's label says it in one sentence.
 */
export default function CumulativeLine({
  current, previous, todayDay, sentence, currentLabel, previousLabel,
}: {
  current: { day: number; total: string }[];
  previous: { day: number; total: string }[];
  todayDay: number | null;
  sentence: string;
  currentLabel: string;
  previousLabel: string;
}) {
  const [table, setTable] = useState(false);
  const data = mergeForChart(current, previous);
  return (
    <div>
      {table ? (
        <table className="tabular w-full text-sm">
          <caption className="sr-only">Running total</caption>
          <thead>
            <tr className="text-left text-ink-muted"><th className="font-normal">Day</th><th className="text-right font-normal">{currentLabel}</th><th className="text-right font-normal">{previousLabel}</th></tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={d.day} className={d.day === todayDay ? "font-semibold" : ""}>
                <td>{d.day}</td>
                <td className="text-right">{d.currentExact ? formatInr(d.currentExact) : "—"}</td>
                <td className="text-right">{d.previousExact ? formatInr(d.previousExact) : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div role="img" aria-label={sentence} className="h-48">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid vertical={false} stroke="#f3f4f6" />
              <XAxis dataKey="day" tickLine={false} axisLine={false} fontSize={12} ticks={[1, 10, 20, data.length]} />
              <YAxis tickLine={false} axisLine={false} fontSize={12} width={56} tickFormatter={(v: number) => compactInr(v.toFixed(2))} />
              <Tooltip formatter={(v) => (typeof v === "number" ? formatInr(v.toFixed(2)) : "—")} labelFormatter={(d) => `Day ${d}`} />
              {todayDay && <ReferenceLine x={todayDay} stroke="#9ca3af" strokeDasharray="2 2" />}
              <Line type="monotone" dataKey="previous" name={previousLabel} stroke="var(--color-compare)" strokeDasharray="5 4" dot={false} isAnimationActive={false} connectNulls />
              <Line type="monotone" dataKey="current" name={currentLabel} stroke="var(--color-accent)" strokeWidth={2.5} dot={false} isAnimationActive={false} connectNulls={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
      <div className="mt-2 flex items-center justify-between text-xs text-ink-muted">
        <span>
          <span aria-hidden className="mr-1 inline-block h-0.5 w-4 bg-accent align-middle" />{currentLabel}
          <span aria-hidden className="ml-3 mr-1 inline-block w-4 border-t-2 border-dashed border-compare align-middle" />{previousLabel}
        </span>
        <button type="button" onClick={() => setTable(!table)} className="underline">{table ? "View as chart" : "View as table"}</button>
      </div>
    </div>
  );
}
