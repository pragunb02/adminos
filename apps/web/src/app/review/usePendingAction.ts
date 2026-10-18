"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { errorMessage } from "@/lib/query";

/**
 * A decision waiting out its Undo window (D-49): hidden at once, sent when the window ends, when a second decision is
 * made, or when the page is left. [label] prefixes a failure; [send] may return a note to show afterwards.
 */
export interface PendingAction {
  key: string;
  message: string;
  label: string;
  send: () => Promise<string | null | void>;
}

export function usePendingAction(onSettled: () => void) {
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const pendingRef = useRef(pending);
  // Kept in a ref so `commit` never changes identity: its change would run the unmount clean-up and send early.
  const settled = useRef(onSettled);
  useEffect(() => {
    settled.current = onSettled;
  }, [onSettled]);
  useEffect(() => {
    pendingRef.current = pending;
  }, [pending]);

  const commit = useCallback(async (p: PendingAction) => {
    try {
      const said = await p.send();
      if (said) setNote(said);
    } catch (e) {
      setFailure(`${p.label}: ${errorMessage(e)}`);
    } finally {
      settled.current();
    }
  }, []);

  const start = (p: PendingAction) => {
    if (pendingRef.current) commit(pendingRef.current); // a second decision sends the first now
    setFailure(null);
    setNote(null);
    pendingRef.current = p;
    setPending(p);
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
  useEffect(() => () => { if (pendingRef.current) commit(pendingRef.current); }, [commit]); // leaving the page sends it

  return { pending, failure, note, start, timeUp, undo };
}
