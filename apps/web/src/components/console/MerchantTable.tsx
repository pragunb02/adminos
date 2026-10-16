import { formatInr, fromPaise, toPaise } from "@/lib/money";
import { categoryLabel, type MerchantRow } from "@/lib/analytics";

/** Average per payment, exact (rounded to the paisa). */
export const averagePerPayment = (spent: string, count: number) =>
  count > 0 ? fromPaise((toPaise(spent) + BigInt(Math.floor(count / 2))) / BigInt(count)) : spent;

/** Every merchant with category, payments, average and total (M/Spending "Merchants"). */
export default function MerchantTable({ rows }: { rows: MerchantRow[] }) {
  return (
    <ul className="divide-y divide-line">
      {rows.map((m) => (
        <li key={m.merchant} className="flex items-center justify-between gap-3 py-2 text-sm">
          <span className="min-w-0">
            <span className="block truncate text-ink">{m.merchant}</span>
            <span className="text-xs text-ink-muted">
              {categoryLabel(m.category)} · {m.count} {m.count === 1 ? "payment" : "payments"}
              {m.count > 1 && ` · ${formatInr(averagePerPayment(m.spent, m.count))} each`}
            </span>
          </span>
          <span className="tabular text-ink">{formatInr(m.spent)}</span>
        </li>
      ))}
    </ul>
  );
}
