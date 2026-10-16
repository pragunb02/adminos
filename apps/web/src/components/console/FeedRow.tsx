import { relativeDate } from "@/lib/format";
import { formatInr } from "@/lib/money";
import { categoryLabel } from "@/lib/analytics";
import type { Transaction } from "@/lib/types";

const NATURE: Record<string, string> = { refund: "Refund", reversal: "Reversal", transfer: "Transfer", income: "Income" };

/** One transaction: merchant, category · method · ··last4, signed amount and a word for its kind (D-49). */
export default function FeedRow({ txn, zone }: { txn: Transaction; zone: string }) {
  return (
    <li>
      <FeedRowContent txn={txn} zone={zone} />
    </li>
  );
}

/** The row's content without the list item, e.g. inside a button (phrasing content only). */
export function FeedRowContent({ txn, zone }: { txn: Transaction; zone: string }) {
  const incoming = txn.type === "credit";
  const meta = [categoryLabel(txn.category), txn.paymentMethod?.toUpperCase(), txn.accountLast4 ? `··${txn.accountLast4}` : null, relativeDate(txn.transactedAt, zone)]
    .filter(Boolean).join(" · ");
  return (
    <span className="flex items-center justify-between py-2 text-sm">
      <span className="min-w-0">
        <span className="block truncate text-ink">{txn.merchantName || "Unknown"}</span>
        <span className="text-xs text-ink-muted">{meta}</span>
      </span>
      <span className="text-right">
        <span className={`tabular block ${incoming ? "text-positive" : "text-ink"}`}>{incoming ? "+" : "−"}{formatInr(txn.amount)}</span>
        {NATURE[txn.nature] && <span className="text-xs text-ink-muted">{NATURE[txn.nature]}</span>}
      </span>
    </span>
  );
}
