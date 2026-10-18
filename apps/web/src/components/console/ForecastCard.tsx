"use client";

import { useQuery } from "@tanstack/react-query";
import Section, { Skeleton } from "@/components/console/Section";
import { formatInr, toPaise } from "@/lib/money";
import {
  dayAndDate, FORECAST_PATH, monthEndDate, NO_SALARY_TEXT, PART_LABEL, shortDate,
  type ForecastData, type ForecastDue, type ForecastPart, type MonthEnd, type SalaryOutlook, type UntilSalary,
} from "@/lib/forecast";
import { errorMessage, getData } from "@/lib/query";
import { accountLabel, SOURCE_LABEL } from "@/lib/balances";

/**
 * Month-end forecast and money left until salary (AOS-714; from recent bank balances when known, AOS-720) on Home. Self-contained: it loads its own data.
 * Every number is the API's (deterministic, D-67); "How we got this" shows the parts that add up to it.
 * Calm (D-49): one figure, plain bars for the parts, words and signs as well as colour.
 */
export default function ForecastCard() {
  const q = useQuery({ queryKey: ["forecast"], queryFn: () => getData<ForecastData>(FORECAST_PATH) });
  return (
    <Section title="Month-end forecast" error={q.error ? errorMessage(q.error) : null} onRetry={() => q.refetch()}>
      {q.data ? <ForecastBody data={q.data} /> : <Skeleton lines={3} />}
    </Section>
  );
}

function ForecastBody({ data }: { data: ForecastData }) {
  if (data.status === "not_enough_history" || !data.monthEnd) {
    const h = data.history;
    return (
      <p className="text-sm text-ink-muted">
        Not enough history to forecast yet{h ? ` (${h.days} of ${h.neededDays} days)` : ""}, so there&apos;s no guess.{" "}
        {h?.readyOn ? `It starts on ${shortDate(h.readyOn)}.` : "Import a statement or connect SMS to start."}
      </p>
    );
  }
  return (
    <div className="space-y-4">
      <MonthEndView m={data.monthEnd} today={data.today} />
      {data.salary && <SalaryView s={data.salary} />}
    </div>
  );
}

function MonthEndView({ m, today }: { m: MonthEnd; today: string }) {
  const end = monthEndDate(m.month);
  const rows = [{ label: "Spent so far", amount: m.spentSoFar }, ...m.parts.filter((p) => toPaise(p.amount) !== 0n).map((p) => ({ label: PART_LABEL[p.kind], amount: p.amount }))];
  return (
    <div>
      <p className="text-sm text-ink-muted">{m.daysLeft === 0 ? `Spent this month, by ${shortDate(end)}` : `By ${shortDate(end)}, at this pace`}</p>
      <p className="tabular text-2xl font-semibold text-ink">About {formatInr(m.total)}</p>
      <p className="tabular mt-1 text-sm text-ink-muted">
        {m.range
          ? `Likely ${formatInr(m.range.low)} to ${formatInr(m.range.high)} (${m.range.level / 10} in 10, from your last ${m.range.months} months)`
          : `A likely range needs ${m.rangeMonthsNeeded} more month${m.rangeMonthsNeeded === 1 ? "" : "s"} of history.`}
      </p>
      <PartBars rows={rows} total={m.total} />
      <details className="mt-3 text-sm">
        <summary className="cursor-pointer text-accent">How we got this</summary>
        <ul className="tabular mt-2 space-y-1 text-ink">
          <li>Spent so far ({shortDate(`${m.month}-01`)}–{shortDate(today)}): {formatInr(m.spentSoFar)}</li>
          {m.parts.map((p) => <PartLine key={p.kind} part={p} prefix="+" />)}
          <li className="font-medium">= About {formatInr(m.total)} by {shortDate(end)}</li>
        </ul>
      </details>
    </div>
  );
}

/** Plain bars, one per part, scaled to the forecast (D-49: bars for comparisons; text says the amount). */
function PartBars({ rows, total }: { rows: { label: string; amount: string }[]; total: string }) {
  const whole = toPaise(total);
  return (
    <ul className="mt-3 space-y-2" aria-label="Parts of the forecast">
      {rows.map((r) => {
        const share = whole > 0n ? Number((toPaise(r.amount) * 1000n) / whole) / 10 : 0;
        return (
          <li key={r.label}>
            <div className="flex items-baseline justify-between gap-2 text-sm">
              <span className="text-ink">{r.label}</span>
              <span className="tabular text-ink">{formatInr(r.amount)}</span>
            </div>
            <div className="mt-1 h-2 rounded-full bg-line" aria-hidden>
              <div className="h-2 rounded-full bg-accent" style={{ width: `${Math.max(2, Math.min(100, share))}%` }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}

const METHOD: Record<string, string> = {
  weekly_median: "your typical week",
  trimmed_mean: "your usual days, leaving out the most unusual",
  median: "your median day",
};

function PartLine({ part, prefix }: { part: ForecastPart; prefix: string }) {
  const label = PART_LABEL[part.kind];
  if (part.kind === "everyday") {
    const learnt = part.learntFrom && part.learntTo ? `, learnt from ${METHOD[part.method ?? ""] ?? "your spending"} ${shortDate(part.learntFrom)}–${shortDate(part.learntTo)}` : "";
    const how = part.split
      ? `${formatInr(part.weekdayRate ?? "0")} a weekday × ${part.weekdays ?? 0} + ${formatInr(part.weekendRate ?? "0")} a weekend day × ${part.weekendDays ?? 0}`
      : `${formatInr(part.weekdayRate ?? "0")} a day × ${(part.weekdays ?? 0) + (part.weekendDays ?? 0)}`;
    return <li>{prefix} {label}: {formatInr(part.amount)} ({how}{learnt})</li>;
  }
  if (part.items.length === 0) return <li className="text-ink-muted">{prefix} {label}: none known</li>;
  if (part.kind === "projected_bills") {
    return (
      <li>
        {prefix} {label}: {formatInr(part.amount)}
        <ul className="mt-1 space-y-1 pl-4 text-ink-muted" aria-label={label}>
          {part.items.map((i) => <li key={`${i.name}-${i.date}`}>{projectedText(i)}</li>)}
        </ul>
      </li>
    );
  }
  return (
    <li>
      {prefix} {label}: {formatInr(part.amount)} (
      {part.items.map((i, n) => (
        <span key={`${i.name}-${i.date}`}>{n > 0 ? ", " : ""}{i.name} {formatInr(i.amount)} {i.overdue ? "overdue, expected" : "on"} {shortDate(i.date)}</span>
      ))}
      )
    </li>
  );
}

/**
 * One bill that usually comes but hasn't yet (AOS-721), e.g. "Sample Power Co · expected 24 Oct · ₹1,240 ·
 * usual amount, billed 4 months running". The name comes from email or SMS: it's only ever a text node.
 */
function projectedText(i: ForecastDue) {
  const seen = i.monthsSeen ? ` · usual amount, billed ${i.monthsSeen} month${i.monthsSeen === 1 ? "" : "s"} running` : "";
  return `${i.name} · expected ${shortDate(i.date)} · ${formatInr(i.amount)}${seen}`;
}

function SalaryView({ s }: { s: SalaryOutlook }) {
  if (s.status === "unknown") {
    return <p className="border-t border-line pt-3 text-sm text-ink-muted">Money left until salary: {NO_SALARY_TEXT[s.reason ?? "no_regular_credit"]}</p>;
  }
  if (s.status === "late" || !s.untilSalary || !s.expectedOn) {
    return (
      <p className="border-t border-line pt-3 text-sm text-ink-muted">
        Your salary usually comes around {s.expectedOn ? shortDate(s.expectedOn) : "now"} and hasn&apos;t arrived yet. Money left will show once it does.
      </p>
    );
  }
  return <UntilSalaryView s={s} u={s.untilSalary} expectedOn={s.expectedOn} />;
}

function UntilSalaryView({ s, u, expectedOn }: { s: SalaryOutlook; u: UntilSalary; expectedOn: string }) {
  const short = u.left.startsWith("-");
  const fromBalance = u.basis === "balance";
  return (
    <div className="border-t border-line pt-3">
      <p className="text-sm text-ink-muted">Salary expected {dayAndDate(expectedOn)}{s.payer ? ` (${s.payer})` : ""}</p>
      <p className="tabular text-xl font-semibold text-ink">
        {short ? <>Short by {formatInr(u.left.slice(1))} before it comes</> : <>{formatInr(u.left)} {fromBalance ? "left before it comes" : "left from this salary"}</>}
      </p>
      {u.leftLow && u.leftHigh && (
        <p className="tabular text-sm text-ink-muted">Likely {formatInr(u.leftLow)} to {formatInr(u.leftHigh)}</p>
      )}
      <details className="mt-2 text-sm">
        <summary className="cursor-pointer text-accent">How we got this</summary>
        <ul className="tabular mt-2 space-y-1 text-ink">
          {fromBalance ? (
            <li>
              In your accounts: {formatInr(u.balance ?? "0")} (
              {u.accounts.map((a, n) => (
                <span key={`${a.bank}-${a.last4}`}>{n > 0 ? ", " : ""}{accountLabel(a.bank, a.last4)} {formatInr(a.amount)} on {shortDate(a.observedOn)} from a {SOURCE_LABEL[a.source]}</span>
              ))}
              )
            </li>
          ) : (
            <li>Last salary: {formatInr(s.lastAmount ?? "0")}{s.lastReceivedOn ? ` on ${shortDate(s.lastReceivedOn)}` : ""}</li>
          )}
          <li>− Spent since{u.spentFrom ? ` ${shortDate(u.spentFrom)}` : ""}: {formatInr(u.spentSince)}</li>
          <li>− Expected {u.from && u.to ? `${shortDate(u.from)}–${shortDate(u.to)}` : "before it (nothing: it's due now)"}: {formatInr(u.expected)}</li>
          {u.parts.filter((p) => toPaise(p.amount) !== 0n).map((p) => <PartLine key={p.kind} part={p} prefix="of which" />)}
          <li className="font-medium">= {short ? `Short by ${formatInr(u.left.slice(1))}` : `${formatInr(u.left)} left`}</li>
        </ul>
        {u.notCounted.length > 0 && (
          <p className="mt-1 text-xs text-ink-muted">
            Not counted:{" "}
            {u.notCounted.map((a, n) => (
              <span key={`${a.bank}-${a.last4}`}>
                {n > 0 ? "; " : ""}{accountLabel(a.bank, a.last4)} {formatInr(a.amount)} on {shortDate(a.observedOn)} ({a.reason === "before_salary" ? "from before the salary came" : `older than ${u.balanceMaxAgeDays} days`})
              </span>
            ))}
            .
          </p>
        )}
        <p className="mt-1 text-xs text-ink-muted">
          {fromBalance
            ? "Starts from your latest bank balances; other income before payday isn't counted."
            : `Other income and savings aren't counted. No bank balance from the last ${u.balanceMaxAgeDays} days is known: a bank SMS with "Avl Bal" or a statement adds one.`}
        </p>
      </details>
    </div>
  );
}
