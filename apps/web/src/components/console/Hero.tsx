import { arrow, delta } from "@/lib/format";
import { formatInr } from "@/lib/money";

/**
 * The headline (M/Home): what was spent so far, and the change against the same days of the previous
 * period — an arrow plus words, never colour alone (D-49).
 */
export default function Hero({
  spent, received, net, comparedSpent, comparedLabel, current,
}: { spent: string; received: string; net: string; comparedSpent: string; comparedLabel: string; current: boolean }) {
  const d = delta(spent, comparedSpent);
  return (
    <div>
      <p className="text-sm text-ink-muted">{current ? "Spent so far" : "Spent"}</p>
      <p className="tabular text-4xl font-semibold leading-tight text-ink">{formatInr(spent)}</p>
      <p className="mt-1 text-sm text-ink-muted">
        {d.direction === "same" ? `Same as ${comparedLabel}` : (
          <>
            <span aria-hidden>{arrow(d.direction)} </span>
            {d.text} than {comparedLabel}
          </>
        )}
      </p>
      <p className="tabular mt-2 text-sm text-ink-muted">
        Received {formatInr(received)} · Net {net.startsWith("-") ? formatInr(net) : `+${formatInr(net)}`}
      </p>
    </div>
  );
}
