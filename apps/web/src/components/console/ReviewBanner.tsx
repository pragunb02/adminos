import Link from "next/link";

/** Data quality before insight (D-49): unconfirmed links change the totals. */
export default function ReviewBanner({ count }: { count: number }) {
  if (count === 0) return null;
  return (
    <Link href="/review" className="flex items-center justify-between rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
      <span>
        <span aria-hidden>⚑ </span>
        {count} to review — they change your totals
      </span>
      <span aria-hidden>›</span>
    </Link>
  );
}
