"use client";

import Link from "next/link";
import { useState } from "react";
import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatInr, toPaise } from "@/lib/money";
import { WEEKDAY_LABEL, type WeekdayNumbers, type Weekday } from "@/lib/leaks";

/**
 * Spending per weekday as columns (D-49: bars only). The chart is an image with a one-sentence label;
 * "View as table" lists each day with its orders and amount, each linking to its transactions.
 */
export default function WeekdayBars({ days, title, hrefFor }: { days: WeekdayNumbers[]; title: string; hrefFor: (d: Weekday) => string }) {
  const [table, setTable] = useState(false);
  const data = days.map((d) => ({ label: WEEKDAY_LABEL[d.day], rupees: Number(toPaise(d.spent)) / 100, spent: d.spent }));
  const top = days.reduce<WeekdayNumbers | null>((m, d) => (m === null || toPaise(d.spent) > toPaise(m.spent) ? d : m), null);
  const summary = top && toPaise(top.spent) > 0n
    ? `${title}: most on ${WEEKDAY_LABEL[top.day]} (${formatInr(top.spent)}, ${top.orders} ${top.orders === 1 ? "order" : "orders"}).`
    : `${title}: nothing spent.`;
  return (
    <div>
      {table ? (
        <table className="tabular w-full text-sm">
          <caption className="sr-only">{title}</caption>
          <thead><tr className="text-left text-ink-muted"><th className="font-normal">Day</th><th className="text-right font-normal">Orders</th><th className="text-right font-normal">Spent</th></tr></thead>
          <tbody>
            {days.map((d) => (
              <tr key={d.day}>
                <td><Link href={hrefFor(d.day)} className="underline">{WEEKDAY_LABEL[d.day]}</Link></td>
                <td className="text-right">{d.orders}</td>
                <td className="text-right">{formatInr(d.spent)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div role="img" aria-label={summary} className="h-28">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 4, right: 0, bottom: 0, left: 0 }}>
              <XAxis dataKey="label" tickLine={false} axisLine={false} fontSize={12} />
              <YAxis hide />
              <Tooltip formatter={(_, __, item) => formatInr((item.payload as { spent: string }).spent)} cursor={{ fill: "#f3f4f6" }} />
              <Bar dataKey="rupees" fill="#93c5fd" radius={[4, 4, 0, 0]} isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
      <div className="mt-1 text-right text-xs text-ink-muted">
        <button type="button" onClick={() => setTable(!table)} className="underline">{table ? "View as chart" : "View days as table"}</button>
      </div>
    </div>
  );
}
