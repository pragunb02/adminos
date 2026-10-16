"use client";

import { useState } from "react";
import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { compactInr } from "@/lib/format";
import { formatInr, toPaise } from "@/lib/money";
import type { SeriesPoint } from "@/lib/analytics";

const WEEKDAY = new Intl.DateTimeFormat("en-IN", { weekday: "short", timeZone: "UTC" });
const dayLabel = (d: string) => WEEKDAY.format(new Date(`${d}T00:00:00Z`));

/**
 * Spending per day as columns, today outlined (M/Home "This week"). The chart is an image with a
 * one-sentence label; "View as table" shows the exact numbers (D-49). Refund-only days can be negative (D-46).
 */
export default function DailyBars({ points, today, title }: { points: SeriesPoint[]; today: string; title: string }) {
  const [table, setTable] = useState(false);
  const data = points.map((p) => ({ day: dayLabel(p.start), date: p.start, rupees: Number(toPaise(p.spent)) / 100, spent: p.spent }));
  const top = points.reduce((m, p) => (toPaise(p.spent) > toPaise(m.spent) ? p : m), points[0] ?? { start: "", spent: "0", received: "0" });
  const todays = points.find((p) => p.start === today);
  const summary = points.length
    ? `${title}: most on ${dayLabel(top.start)} (${formatInr(top.spent)})${todays ? `, today ${formatInr(todays.spent)}` : ""}.`
    : `${title}: nothing yet.`;
  return (
    <div>
      {table ? (
        <table className="tabular w-full text-sm">
          <caption className="sr-only">{title}</caption>
          <thead><tr className="text-left text-ink-muted"><th className="font-normal">Day</th><th className="text-right font-normal">Spent</th></tr></thead>
          <tbody>
            {points.map((p) => (
              <tr key={p.start} className={p.start === today ? "font-semibold" : ""}>
                <td>{dayLabel(p.start)} {p.start.slice(8)}</td>
                <td className="text-right">{formatInr(p.spent)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div role="img" aria-label={summary} className="h-40">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 4, right: 0, bottom: 0, left: 0 }}>
              <XAxis dataKey="day" tickLine={false} axisLine={false} fontSize={12} />
              <YAxis hide />
              <Tooltip formatter={(_, __, item) => formatInr((item.payload as { spent: string }).spent)} cursor={{ fill: "#f3f4f6" }} />
              <Bar dataKey="rupees" radius={[4, 4, 0, 0]} isAnimationActive={false}>
                {data.map((d) => (
                  <Cell key={d.date} fill={d.date === today ? "var(--color-accent)" : "#93c5fd"} stroke={d.date === today ? "#1e3a8a" : undefined} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
      <div className="mt-2 flex items-center justify-between text-xs text-ink-muted">
        <span className="tabular">{todays ? `Today ${formatInr(todays.spent)}` : ""}{points.length > 0 && !table ? ` · axis ${compactInr(top.spent)}` : ""}</span>
        <button type="button" onClick={() => setTable(!table)} className="underline">
          {table ? "View as chart" : "View as table"}
        </button>
      </div>
    </div>
  );
}
