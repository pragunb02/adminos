"use client";

import { useEffect, useRef } from "react";

/** "Done. Undo" for 5 s (D-49: undo instead of "are you sure?"). Focusable; announced politely. */
export default function UndoToast({ message, onUndo, onClose, ms = 5000 }: { message: string; onUndo: () => void; onClose: () => void; ms?: number }) {
  // The timer runs once per message: a parent re-render (new onClose function) must not restart it.
  const close = useRef(onClose);
  useEffect(() => {
    close.current = onClose;
  }, [onClose]);
  useEffect(() => {
    const t = setTimeout(() => close.current(), ms);
    return () => clearTimeout(t);
  }, [ms, message]);
  return (
    <div role="status" className="fixed inset-x-4 bottom-24 z-50 mx-auto flex max-w-md items-center justify-between gap-3 rounded-lg bg-ink px-4 py-3 text-sm text-white shadow-lg lg:bottom-6">
      <span>{message}</span>
      <button type="button" onClick={onUndo} className="min-h-11 rounded px-2 font-semibold text-blue-200 underline">Undo</button>
    </div>
  );
}
