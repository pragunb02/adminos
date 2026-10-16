import Link from "next/link";
import { arrow, delta } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { categoryLabel, type CategoryRow } from "@/lib/analytics";

/**
 * Sorted horizontal bars with direct labels: name, ₹, share, and the change in words (D-49: no pies,
 * no legend). Plain HTML/CSS so every bar is readable by screen readers. A refund-only category shows
 * as negative with a label (D-46).
 */
export default function CategoryBars({ rows, limit, hrefFor }: { rows: CategoryRow[]; limit?: number; hrefFor?: (c: string) => string }) {
  const shown = limit ? rows.slice(0, limit) : rows;
  return (
    <ul className="space-y-3">
      {shown.map((r) => {
        const d = delta(r.spent, r.previousSpent);
        const width = Math.max(2, Math.round(r.share * 100));
        const refundsOnly = r.spent.startsWith("-");
        const body = (
          <>
            <div className="flex items-baseline justify-between gap-2 text-sm">
              <span className="text-ink">{categoryLabel(r.category)}{refundsOnly && <span className="ml-1 text-xs text-ink-muted">(refunds only)</span>}</span>
              <span className="tabular text-ink">
                {formatInr(r.spent)} <span className="text-ink-muted">{Math.round(r.share * 100)}%</span>
              </span>
            </div>
            <div className="mt-1 h-2 rounded-full bg-line" aria-hidden>
              <div className="h-2 rounded-full" style={{ width: `${refundsOnly ? 0 : width}%`, backgroundColor: `var(--color-cat-${r.category}, var(--color-cat-other))` }} />
            </div>
            {d.direction !== "same" && (
              <p className="tabular mt-0.5 text-xs text-ink-muted"><span aria-hidden>{arrow(d.direction)} </span>{d.text} than before</p>
            )}
          </>
        );
        return <li key={r.category}>{hrefFor ? <Link href={hrefFor(r.category)} className="block hover:opacity-80">{body}</Link> : body}</li>;
      })}
    </ul>
  );
}
