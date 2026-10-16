import { formatInr } from "@/lib/money";
import { categoryLabel, type MerchantRow } from "@/lib/analytics";

/** Where most money goes: name, category, visits, total. */
export default function MerchantList({ rows }: { rows: MerchantRow[] }) {
  return (
    <ul className="divide-y divide-line">
      {rows.map((m) => (
        <li key={m.merchant} className="flex items-center justify-between py-2 text-sm">
          <span className="min-w-0">
            <span className="block truncate text-ink">{m.merchant}</span>
            <span className="text-xs text-ink-muted">{categoryLabel(m.category)} · {m.count} {m.count === 1 ? "payment" : "payments"}</span>
          </span>
          <span className="tabular text-ink">{formatInr(m.spent)}</span>
        </li>
      ))}
    </ul>
  );
}
