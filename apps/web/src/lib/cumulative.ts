import { fromPaise, toPaise } from "./money";
import type { SeriesPoint } from "./analytics";

/** Running total by day of month, exact (paise). Not clipped: a refund-only day lowers it (D-46). */
export function cumulativeByDay(points: SeriesPoint[]): { day: number; total: string }[] {
  let sum = 0n;
  return points.map((p) => {
    sum += toPaise(p.spent);
    return { day: Number(p.start.slice(8, 10)), total: fromPaise(sum) };
  });
}

/** Merge this month's and last month's running totals on day 1…max(lengths) for the chart. */
export function mergeForChart(current: { day: number; total: string }[], previous: { day: number; total: string }[]) {
  const days = Math.max(current.length ? current[current.length - 1].day : 0, previous.length ? previous[previous.length - 1].day : 0);
  const byDay = (rows: { day: number; total: string }[]) => new Map(rows.map((r) => [r.day, r.total]));
  const c = byDay(current);
  const p = byDay(previous);
  return Array.from({ length: days }, (_, i) => {
    const day = i + 1;
    const cur = c.get(day);
    const prev = p.get(day);
    return {
      day,
      current: cur === undefined ? null : Number(toPaise(cur)) / 100,
      previous: prev === undefined ? null : Number(toPaise(prev)) / 100,
      currentExact: cur ?? null,
      previousExact: prev ?? null,
    };
  });
}
