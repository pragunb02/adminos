"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import Section, { Skeleton } from "@/components/console/Section";
import { currentText, GOALS_PATH, goalTitle, streakText, type Goal } from "@/lib/goals";
import { errorMessage, getData } from "@/lib/query";

/** Home's goals card (AOS-907): each goal's period so far and its streak. Quiet (renders nothing) without goals. */
export default function GoalsHomeCard() {
  const goals = useQuery({ queryKey: ["coach", "goals"], queryFn: () => getData<Goal[]>(GOALS_PATH) });
  if (goals.data && goals.data.length === 0) return null;
  return (
    <Section title="Goals" action={<Link href="/leaks" className="text-xs text-accent">All ›</Link>}
      error={goals.error ? errorMessage(goals.error) : null} onRetry={() => goals.refetch()}>
      {goals.data ? (
        <ul className="space-y-2 text-sm">
          {goals.data.map((g) => (
            <li key={g.id}>
              <p className="text-ink">{goalTitle(g)}</p>
              <p className="tabular text-xs text-ink-muted">{currentText(g)} · {streakText(g)}</p>
            </li>
          ))}
        </ul>
      ) : <Skeleton lines={2} />}
    </Section>
  );
}
