"use client";

import { useState } from "react";
import { errorMessage, patchData, postData } from "@/lib/query";
import type { DocumentField, FieldProposal, FieldProposals, VaultDocument } from "@/lib/types";

const LABELS: Record<DocumentField, string> = { expiresOn: "Expires on", number: "Number", issuer: "Issued by", amount: "Amount paid" };
const SOURCE: Record<FieldProposals["source"], string> = {
  text_layer: "Read from the PDF's own text.",
  ocr: "Read from the scan on this server (OCR): check each value.",
  mixed: "Read from the PDF's text and, for scanned pages, by OCR on this server: check each value.",
};
const AI_NOTE: Partial<Record<FieldProposals["ai"], string>> = {
  identity: "Identity documents are never sent to an AI model.",
  no_consent: "AI help is off for documents (Settings → AI features). Only the built-in rules were used.",
  rejected: "The AI's answer couldn't be found in the document, so it was ignored.",
  unavailable: "The AI model wasn't available; only the built-in rules were used.",
};

/** What a proposal looks like to the owner. */
export function shownValue(p: Pick<FieldProposal, "field" | "value">): string {
  if (p.field === "number") return `•••• ${p.value}`;
  if (p.field === "amount") return `₹${Number(p.value).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  if (p.field === "expiresOn") return new Date(`${p.value}T00:00:00Z`).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
  return p.value;
}

/** The PATCH body for the chosen proposals: the number as its last 4 only. */
export function patchFor(chosen: FieldProposal[]): Record<string, string> {
  const body: Record<string, string> = {};
  for (const p of chosen) body[p.field === "number" ? "numberLast4" : p.field] = p.value;
  return body;
}

/**
 * Read a vault document's fields (AOS-717): the server reads the text (or OCRs a scan) and proposes; the owner ticks
 * what is right and saves. Nothing changes until "Save". All server text is rendered as text, never as HTML.
 */
export default function ReadFields({ doc, onSaved }: { doc: VaultDocument; onSaved: () => void }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<FieldProposals | null>(null);
  const [chosen, setChosen] = useState<Set<DocumentField>>(new Set());
  const [failure, setFailure] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const read = async () => {
    setBusy(true); setFailure(null); setSaved(null);
    try {
      const r = await postData<FieldProposals>(`/api/v1/documents/${doc.id}/read`, {});
      setResult(r);
      setChosen(new Set(r.proposals.map((p) => p.field)));
    } catch (e) {
      setFailure(errorMessage(e, "The document couldn't be read"));
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!result) return;
    const picked = result.proposals.filter((p) => chosen.has(p.field));
    if (picked.length === 0) return;
    setBusy(true); setFailure(null);
    try {
      await patchData(`/api/v1/documents/${doc.id}`, patchFor(picked));
      setSaved(`${picked.length} field${picked.length === 1 ? "" : "s"} saved.${picked.some((p) => p.field === "expiresOn") ? " You'll be reminded before it expires." : ""}`);
      setResult(null);
      onSaved();
    } catch (e) {
      setFailure(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const toggle = (f: DocumentField) => setChosen((s) => { const n = new Set(s); if (n.has(f)) n.delete(f); else n.add(f); return n; });

  return (
    <div className="mt-2 space-y-2">
      {!result && (
        <button type="button" onClick={read} disabled={busy} className="min-h-9 rounded-md border border-line px-3 text-sm disabled:opacity-60">
          {busy ? "Reading…" : "Read fields"}
        </button>
      )}
      {result && (
        <section aria-label={`Proposed fields for ${doc.title}`} className="rounded-md border border-line p-3 text-sm">
          <p className="text-xs text-ink-muted">{SOURCE[result.source]}{result.truncated ? ` Only the first ${result.pagesRead} of ${result.pagesTotal} pages were read.` : ""}</p>
          {result.proposals.length === 0 ? (
            <p className="mt-2">Nothing could be read with confidence. Type the details with Edit instead.</p>
          ) : (
            <ul className="mt-2 space-y-2">
              {result.proposals.map((p) => (
                <li key={p.field}>
                  <label className="flex items-start gap-2">
                    <input type="checkbox" checked={chosen.has(p.field)} onChange={() => toggle(p.field)} className="mt-1" />
                    <span>
                      <span className="font-medium">{LABELS[p.field]}: {shownValue(p)}</span>
                      {p.from === "ai" && <span className="ml-1 text-xs text-ink-muted">(suggested by AI)</span>}
                      <span className="block text-xs text-ink-muted">“{p.context}”</span>
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
          {AI_NOTE[result.ai] && <p className="mt-2 text-xs text-ink-muted">{AI_NOTE[result.ai]}</p>}
          <div className="mt-3 flex gap-2">
            <button type="button" onClick={save} disabled={busy || chosen.size === 0 || result.proposals.length === 0}
              className="min-h-9 rounded-md bg-accent px-3 text-white disabled:opacity-60">Save selected</button>
            <button type="button" onClick={() => setResult(null)} className="min-h-9 rounded-md border border-line px-3">Not now</button>
          </div>
        </section>
      )}
      {saved && <p role="status" className="text-sm">{saved}</p>}
      {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}
    </div>
  );
}
