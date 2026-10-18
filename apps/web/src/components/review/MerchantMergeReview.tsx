"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import UndoToast from "@/components/console/UndoToast";
import { errorMessage, getData, postData } from "@/lib/query";
import type { MergeProposal } from "@/lib/types";

/** How long the Undo window is; the answer is sent when it ends (a merge changes subscriptions and lists). */
const UNDO_MS = 5000;
const PATH = "/api/v1/merchants/merge-proposals";

type Pending = { p: MergeProposal; same: boolean };

/**
 * Review → "Same merchant?" (AOS-709): look-alike merchant names AdminOS proposes as one. One tap
 * answers; the card hides at once and the answer is sent when the Undo window ends (or the page is
 * left). A separate section, so it stays out of the transaction-pair queue's way. Renders nothing
 * while there is nothing to ask.
 */
export default function MerchantMergeReview() {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ["merchant-merges"], queryFn: () => getData<MergeProposal[]>(PATH) });
  const [pending, setPending] = useState<Pending | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const pendingRef = useRef<Pending | null>(null);

  const commit = useCallback(async ({ p, same }: Pending) => {
    try {
      await postData(`${PATH}/${p.id}/${same ? "confirm" : "reject"}`, {});
    } catch (e) {
      setFailure(`${p.name}: ${errorMessage(e)}`);
    } finally {
      for (const key of [["merchant-merges"], ["subscriptions"], ["analytics"]]) queryClient.invalidateQueries({ queryKey: key });
    }
  }, [queryClient]);

  const answer = (p: MergeProposal, same: boolean) => {
    if (pendingRef.current) commit(pendingRef.current); // a second answer sends the first now
    setFailure(null);
    pendingRef.current = { p, same };
    setPending({ p, same });
  };
  const timeUp = () => {
    const p = pendingRef.current;
    pendingRef.current = null;
    setPending(null);
    if (p) commit(p);
  };
  const undo = () => {
    pendingRef.current = null;
    setPending(null);
  };
  useEffect(() => () => { if (pendingRef.current) commit(pendingRef.current); }, [commit]); // leaving sends it

  const visible = (list.data ?? []).filter((p) => p.id !== pending?.p.id);
  if (list.isPending || (!list.error && visible.length === 0 && !pending && !failure)) return null;

  return (
    <section aria-labelledby="merchant-merges" className="space-y-3">
      <h3 id="merchant-merges" className="text-base font-semibold">Same merchant?</h3>
      <p className="text-sm text-ink-muted">These names look like one place. Merging them makes one subscription and one line in your spending.</p>
      {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}
      {list.error ? (
        <p role="alert" className="text-sm text-negative">
          {errorMessage(list.error)} <button type="button" className="underline" onClick={() => list.refetch()}>Try again</button>
        </p>
      ) : (
        <ul className="space-y-3">
          {visible.map((p) => (
            <li key={p.id} aria-label={`Same merchant: ${p.name}?`} className="rounded-xl border border-line bg-surface p-4">
              <p className="text-sm font-semibold">Are these all {p.name}?</p>
              <ul className="mt-2 space-y-1 text-sm">
                {p.names.map((n) => (
                  <li key={n.name} className="flex justify-between gap-2 rounded-lg bg-page px-3 py-2">
                    <span className="truncate">{n.name}</span>
                    <span className="tabular text-xs text-ink-muted">{n.payments === 1 ? "1 payment" : `${n.payments} payments`}</span>
                  </li>
                ))}
              </ul>
              <p className="mt-2 text-xs text-ink-muted">{p.reasons.join(" · ")}</p>
              <div className="mt-3 flex gap-2">
                <button type="button" onClick={() => answer(p, true)} className="h-11 flex-1 rounded-lg bg-accent text-sm font-semibold text-white">Same merchant</button>
                <button type="button" onClick={() => answer(p, false)} className="h-11 flex-1 rounded-lg border border-line text-sm">Different</button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {pending && (
        <UndoToast key={pending.p.id} ms={UNDO_MS} onUndo={undo} onClose={timeUp}
          message={pending.same ? `Merging into ${pending.p.name}` : "Kept as separate merchants"} />
      )}
    </section>
  );
}
