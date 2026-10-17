"use client";

import { useState } from "react";

/** Recovery codes, shown once (AOS-617): the owner copies them, then confirms they're kept somewhere safe. */
export default function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    await navigator.clipboard.writeText(codes.join("\n"));
    setCopied(true);
  };
  return (
    <section aria-labelledby="codes-heading" className="rounded-lg border border-amber-300 bg-amber-50 p-4">
      <h3 id="codes-heading" className="font-semibold text-amber-900">Save your recovery codes</h3>
      <p className="mt-1 text-sm text-amber-900">
        If you lose your passkey, each code lets you in once. They won&apos;t be shown again. Keep them in your password manager or on paper, not on this device alone.
      </p>
      <ol aria-label="Recovery codes" className="mt-3 grid grid-cols-2 gap-2 font-mono text-sm">
        {codes.map((c) => <li key={c} className="rounded-sm bg-white px-2 py-1">{c}</li>)}
      </ol>
      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" onClick={copy} className="min-h-11 rounded-md border border-amber-400 px-4 text-sm text-amber-900">
          {copied ? "Copied ✓" : "Copy codes"}
        </button>
        <button type="button" onClick={onDone} className="min-h-11 rounded-md bg-amber-700 px-4 text-sm font-semibold text-white">
          I&apos;ve saved them
        </button>
      </div>
    </section>
  );
}
