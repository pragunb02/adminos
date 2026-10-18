"use client";

import { useState, type FormEvent, type KeyboardEvent } from "react";
import { api } from "@/lib/api";
import { addMoney, formatInr } from "@/lib/money";
import {
  CASH_CATEGORIES, CASH_PATH, MAX_TEXT, MORE_CATEGORIES, PARSE_PATH, UNPARSED_TEXT, validAmount, WARNING_TEXT,
  type CashParse, type DraftWarning,
} from "@/lib/cash";

const OFFLINE = "Couldn't reach AdminOS. Check the connection and try again.";

/** One entry in the preview, as the user may have edited it. [clientRef] is fixed per entry, so a retry is saved once. */
interface Draft {
  clientRef: string;
  amount: string;
  category: string;
  note: string;
  date: string;
  warnings: DraftWarning[];
  error: string | null;
}

/**
 * Cash from free text (AOS-718): "200 chai and 150 auto yesterday" → a preview of entries to check, edit or remove,
 * then save. Typed or dictated (the phone keyboard's mic writes into the same box). Nothing is saved until "Save":
 * each entry then goes to the same cash endpoint as the quick add (D-42).
 */
export default function CashTextEntry({ onSaved }: { onSaved: (message: string) => void }) {
  const [text, setText] = useState("");
  const [reading, setReading] = useState(false);
  const [drafts, setDrafts] = useState<Draft[] | null>(null);
  const [unparsed, setUnparsed] = useState<CashParse["unparsed"]>([]);
  const [today, setToday] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const read = async (e?: FormEvent) => {
    e?.preventDefault();
    if (!text.trim() || reading) return;
    setReading(true);
    setError(null);
    const res = await api.post<CashParse>(PARSE_PATH, { text }).catch(() => null);
    setReading(false);
    if (!res) {
      setError(OFFLINE);
      return;
    }
    if (!res.success || !res.data) {
      setError(res.error?.message ?? "Couldn't read that. Try again.");
      return;
    }
    setToday(res.data.today);
    setUnparsed(res.data.unparsed);
    setDrafts(res.data.entries.map((p) => ({
      clientRef: crypto.randomUUID(), amount: p.amount.replace(/\.00$/, ""), category: p.category, note: p.note ?? "", date: p.date, warnings: p.warnings, error: null,
    })));
  };

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void read();
  };

  const edit = (ref: string, change: Partial<Draft>) => setDrafts((ds) => ds?.map((d) => (d.clientRef === ref ? { ...d, ...change, error: null } : d)) ?? null);
  const remove = (ref: string) => setDrafts((ds) => ds?.filter((d) => d.clientRef !== ref) ?? null);
  const startOver = () => { setDrafts(null); setUnparsed([]); setError(null); };

  const problem = (d: Draft) =>
    !validAmount(d.amount) ? "Type an amount, e.g. 40 or 249.50." : !/^\d{4}-\d{2}-\d{2}$/.test(d.date) || d.date > today ? "Pick a day up to today." : null;
  const ready = !!drafts && drafts.length > 0 && drafts.every((d) => problem(d) === null);

  const save = async () => {
    if (!drafts || !ready || saving) return;
    setSaving(true);
    setError(null);
    const failed: Draft[] = [];
    const saved: string[] = [];
    for (const d of drafts) {
      const res = await api.post<{ saved: boolean; duplicate: boolean }>(CASH_PATH, {
        clientRef: d.clientRef, amount: d.amount, category: d.category, note: d.note.trim() || undefined, date: d.date,
      }).catch(() => null);
      if (res?.success) saved.push(d.amount);
      else failed.push({ ...d, error: res ? res.error?.message ?? "Couldn't save. Try again." : OFFLINE });
    }
    setSaving(false);
    if (failed.length === 0) {
      onSaved(`${saved.length} cash ${saved.length === 1 ? "entry" : "entries"} saved (${formatInr(addMoney(...saved))})`);
      return;
    }
    setDrafts(failed);
    setError(`${failed.length} not saved yet: check ${failed.length === 1 ? "it" : "them"} and save again.`);
  };

  return (
    <div className="space-y-3 border-t border-gray-200 pt-4">
      {drafts === null ? (
        <form onSubmit={read} className="space-y-2">
          <label htmlFor="cash-text" className="block text-sm font-medium text-gray-700">Or say it in words</label>
          <textarea
            id="cash-text"
            rows={2}
            maxLength={MAX_TEXT}
            inputMode="text"
            enterKeyHint="done"
            placeholder="e.g. 200 chai and 150 auto yesterday (type, or tap the mic on your keyboard)"
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKey}
            className="w-full rounded-md border border-gray-200 px-3 py-2 text-sm"
          />
          <button type="submit" disabled={!text.trim() || reading} className="rounded-md bg-emerald-700 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40">
            {reading ? "Reading…" : "Read it"}
          </button>
        </form>
      ) : (
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-gray-900">Check before saving</h3>
          {drafts.length === 0 && <p className="text-sm text-gray-600">Nothing to save yet.</p>}
          <ul className="space-y-3">
            {drafts.map((d, i) => <DraftRow key={d.clientRef} d={d} n={i + 1} today={today} problem={problem(d)} onEdit={edit} onRemove={remove} />)}
          </ul>
          {unparsed.length > 0 && (
            <div className="text-sm text-gray-700">
              <p className="font-medium">Couldn&apos;t read:</p>
              <ul className="list-disc pl-5">
                {unparsed.map((u, i) => <li key={`${u.text}-${i}`}>“{u.text}”: {UNPARSED_TEXT[u.reason] ?? "not understood"}</li>)}
              </ul>
            </div>
          )}
          <div className="flex gap-2">
            <button type="button" onClick={save} disabled={!ready || saving} className="rounded-md bg-emerald-700 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40">
              {saving ? "Saving…" : `Save ${drafts.length} ${drafts.length === 1 ? "entry" : "entries"}`}
            </button>
            <button type="button" onClick={startOver} className="rounded-md border border-gray-200 px-4 py-2 text-sm">Start over</button>
          </div>
        </div>
      )}
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
    </div>
  );
}

function DraftRow({ d, n, today, problem, onEdit, onRemove }: {
  d: Draft; n: number; today: string; problem: string | null;
  onEdit: (ref: string, change: Partial<Draft>) => void; onRemove: (ref: string) => void;
}) {
  const options = [...CASH_CATEGORIES, ...MORE_CATEGORIES.filter((c) => c.value === d.category)];
  return (
    <li className="rounded-lg border border-gray-200 p-3">
      <div className="grid grid-cols-2 gap-2 text-sm">
        <label className="flex flex-col">
          <span className="text-xs text-gray-600">Amount (₹)</span>
          <input aria-label={`Amount for entry ${n}`} inputMode="decimal" value={d.amount} onChange={(e) => onEdit(d.clientRef, { amount: e.target.value.replace(/[^\d.]/g, "") })} className="rounded-md border border-gray-200 px-2 py-1" />
        </label>
        <label className="flex flex-col">
          <span className="text-xs text-gray-600">Category</span>
          <select aria-label={`Category for entry ${n}`} value={d.category} onChange={(e) => onEdit(d.clientRef, { category: e.target.value, warnings: d.warnings.filter((w) => w !== "category_unknown") })} className="rounded-md border border-gray-200 px-2 py-1">
            {options.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
        </label>
        <label className="flex flex-col">
          <span className="text-xs text-gray-600">Day</span>
          <input aria-label={`Day for entry ${n}`} type="date" max={today} value={d.date} onChange={(e) => onEdit(d.clientRef, { date: e.target.value })} className="rounded-md border border-gray-200 px-2 py-1" />
        </label>
        <label className="flex flex-col">
          <span className="text-xs text-gray-600">Note</span>
          <input aria-label={`Note for entry ${n}`} maxLength={255} value={d.note} onChange={(e) => onEdit(d.clientRef, { note: e.target.value })} className="rounded-md border border-gray-200 px-2 py-1" />
        </label>
      </div>
      {d.warnings.length > 0 && (
        <ul className="mt-2 text-xs text-amber-900">
          {d.warnings.map((w) => <li key={w}><span aria-hidden>⚠ </span>{WARNING_TEXT[w] ?? w}</li>)}
        </ul>
      )}
      {problem && <p className="mt-1 text-xs text-red-700">{problem}</p>}
      {d.error && <p className="mt-1 text-xs text-red-700">{d.error}</p>}
      <button type="button" onClick={() => onRemove(d.clientRef)} aria-label={`Remove entry ${n}`} className="mt-2 text-xs underline">Remove</button>
    </li>
  );
}
