"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import Section, { Skeleton } from "@/components/console/Section";
import { formatInr } from "@/lib/money";
import { accountLabel, ageText, BALANCES_PATH, SOURCE_LABEL, type AccountBalance, type BalancesData } from "@/lib/balances";
import { errorMessage, getData } from "@/lib/query";

/**
 * Bank balances (AOS-720, D-170): each account's latest known balance, its age in words and where it came from.
 * Self-contained: it loads its own data. `full` lists the explanation too (the /balances page).
 */
export default function BalancesCard({ full = false }: { full?: boolean }) {
  const q = useQuery({ queryKey: ["balances"], queryFn: () => getData<BalancesData>(BALANCES_PATH) });
  const action = full ? undefined : <Link href="/balances" className="text-xs text-accent">All ›</Link>;
  return (
    <Section title="Bank balances" action={action} error={q.error ? errorMessage(q.error) : null} onRetry={() => q.refetch()}>
      {q.data ? <BalancesBody data={q.data} full={full} /> : <Skeleton lines={2} />}
    </Section>
  );
}

function BalancesBody({ data, full }: { data: BalancesData; full: boolean }) {
  if (data.accounts.length === 0) {
    return (
      <p className="text-sm text-ink-muted">
        No balance known yet. A bank SMS that shows &quot;Avl Bal&quot;, or an imported statement, adds one.
      </p>
    );
  }
  return (
    <div>
      <p className="text-sm text-ink-muted">
        {data.countedAccounts > 0
          ? `In ${data.countedAccounts} account${data.countedAccounts === 1 ? "" : "s"} from the last ${data.maxAgeDays} days`
          : `None from the last ${data.maxAgeDays} days`}
      </p>
      {data.countedAccounts > 0 && <p className="tabular text-xl font-semibold text-ink">{formatInr(data.total)}</p>}
      <ul className="mt-3 divide-y divide-line" aria-label="Accounts">
        {data.accounts.map((a) => <AccountRow key={`${a.bank}-${a.last4}`} a={a} maxAgeDays={data.maxAgeDays} />)}
      </ul>
      {full && (
        <div className="mt-4 space-y-1 text-xs text-ink-muted">
          <p>Each account shows the newest balance a bank SMS or a statement gave, by when it was true, never by when it arrived.</p>
          <p>Only SMS from banks AdminOS knows count. Credit cards, wallets and loans are never balances.</p>
          <p>Balances older than {data.maxAgeDays} days are shown but not counted in the total or in money left until salary.</p>
        </div>
      )}
    </div>
  );
}

function AccountRow({ a, maxAgeDays }: { a: AccountBalance; maxAgeDays: number }) {
  const overdrawn = a.amount.startsWith("-");
  return (
    <li className="flex items-baseline justify-between gap-2 py-2 text-sm">
      <span>
        <span className="text-ink">{accountLabel(a.bank, a.last4)}</span>
        <span className={`block text-xs ${a.counted ? "text-ink-muted" : "text-negative"}`}>
          {ageText(a, maxAgeDays)} · from a {SOURCE_LABEL[a.source]}
        </span>
      </span>
      <span className={`tabular ${a.counted ? "text-ink" : "text-ink-muted line-through"}`}>
        {overdrawn ? `Overdrawn ${formatInr(a.amount.slice(1))}` : formatInr(a.amount)}
      </span>
    </li>
  );
}
