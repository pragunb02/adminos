"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import Section, { Skeleton } from "@/components/console/Section";
import { periodOnly } from "@/lib/consolePeriod";
import { leaksPath, type LeaksData } from "@/lib/leaks";
import { formatInr, toPaise } from "@/lib/money";
import type { Range } from "@/lib/period";
import { errorMessage, getData } from "@/lib/query";

/**
 * Home's "Where it leaks" card (AOS-902): the period's leak total and its biggest lens, linking to the
 * leaks page with the same period. Self-contained, so Home only places it.
 */
export default function LeaksHomeCard({ range }: { range: Range }) {
  const query = periodOnly(useSearchParams());
  const leaks = useQuery({ queryKey: ["coach", "leaks", range.from, range.to], queryFn: () => getData<LeaksData>(leaksPath(range)) });
  const top = leaks.data?.lenses.find((l) => toPaise(l.numbers.spent) > 0n);
  return (
    <Section title="Where it leaks" action={<Link href={`/leaks${query}`} className="text-xs text-accent">All ›</Link>}
      error={leaks.error ? errorMessage(leaks.error) : null} onRetry={() => leaks.refetch()}>
      {leaks.data ? (
        top ? (
          <p className="tabular text-sm text-ink-muted">
            <span className="font-semibold text-ink">{formatInr(leaks.data.total.spent)}</span> on habits over {leaks.data.total.orders} {leaks.data.total.orders === 1 ? "order" : "orders"}.
            {" "}Biggest: <Link href={`/leaks${query}`} className="text-ink underline decoration-line underline-offset-2">{top.label} {formatInr(top.numbers.spent)}</Link>
            {top.numbers.lateNight.orders > 0 && <> ({top.numbers.lateNight.orders} late at night)</>}.
          </p>
        ) : (
          <p className="text-sm text-ink-muted">No habit spending in this period.</p>
        )
      ) : <Skeleton lines={2} />}
    </Section>
  );
}
