"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { deleteData, errorMessage, getData } from "@/lib/query";
import { exportMembers, type SplitImportItem, type SplitImportResult } from "@/lib/splitwise";

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
const NOT_AN_EXPORT = "This doesn't look like a Splitwise export. In Splitwise, open the group, then Settings → Export as spreadsheet.";

/** Everything a Splitwise import changes is shown here and in Review. */
function invalidate(client: ReturnType<typeof useQueryClient>) {
  for (const key of [["splitwise"], ["analytics"], ["transactions"]]) client.invalidateQueries({ queryKey: key });
}

/**
 * Import shared bills from Splitwise (AOS-911, D-111). The owner picks which column of the export is them (read
 * from the file's header in the browser), then the file is sent once and not kept. Only their share counts as
 * spending; settle-ups stop counting as spending or income. Past imports can be undone.
 */
export default function SplitwiseImport() {
  const client = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [members, setMembers] = useState<string[]>([]);
  const [me, setMe] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SplitImportResult | null>(null);

  const choose = async (picked: File | null) => {
    setFile(null); setMembers([]); setMe(""); setResult(null); setError(null);
    if (!picked) return;
    const found = exportMembers(await picked.slice(0, 64 * 1024).text());
    if (!found) { setError(NOT_AN_EXPORT); return; }
    setFile(picked);
    setMembers(found);
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || !me) return;
    setBusy(true); setError(null); setResult(null);
    const form = new FormData();
    form.append("file", file);
    form.append("me", me);
    const res = await api.upload<SplitImportResult>("/api/v1/imports/splitwise", form);
    setBusy(false);
    if (res.success && res.data) { setResult(res.data); invalidate(client); }
    else setError(res.error?.message ?? "The export couldn't be imported.");
  };

  return (
    <section aria-labelledby="splitwise-import" className="mt-8">
      <h2 id="splitwise-import" className="text-xl font-bold mb-1">Import from Splitwise</h2>
      <p className="text-sm text-gray-500 mb-2">
        Bills you split with friends count only your share. The rest is owed to you, and money friends pay back
        isn&apos;t income. The file is used once and not kept; importing the same export again adds only new rows.
      </p>
      <details className="mb-3 text-sm text-gray-600">
        <summary className="cursor-pointer text-blue-600">How to export from Splitwise</summary>
        <ol className="mt-1 list-decimal pl-5 space-y-0.5">
          <li>Open the group (or friend) in Splitwise on the web or the app.</li>
          <li>Open its settings and choose <strong>Export as spreadsheet</strong>.</li>
          <li>Choose the CSV here. Import group exports; a friend&apos;s export can repeat the same bills.</li>
        </ol>
      </details>

      <form onSubmit={submit} className="bg-white border border-gray-200 rounded-lg p-4 space-y-3">
        <input
          type="file"
          aria-label="Splitwise export"
          accept=".csv,text/csv"
          onChange={(e) => choose(e.target.files?.[0] ?? null)}
          className="block w-full text-sm"
        />
        {members.length > 0 && (
          <label className="block text-sm">
            <span className="text-gray-700">Which one is you?</span>
            <select value={me} onChange={(e) => setMe(e.target.value)} className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-1.5">
              <option value="">Choose your name</option>
              {members.map((m, i) => <option key={`${i}-${m}`} value={m}>{m}</option>)}
            </select>
          </label>
        )}
        <button type="submit" disabled={!file || !me || busy} className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50">
          {busy ? "Importing…" : "Import from Splitwise"}
        </button>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      </form>

      {result && (
        <section role="status" aria-label="Splitwise import result" className="mt-4 bg-white border border-green-200 rounded-lg p-4">
          <h3 className="font-semibold text-green-800">{plural(result.rows, "row")} read, {result.applied} applied</h3>
          <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm text-gray-700">
            <dt className="text-gray-500">To review</dt><dd>{result.suggested}</dd>
            <dt className="text-gray-500">Waiting for a bank record</dt><dd>{result.unmatched}</dd>
            <dt className="text-gray-500">Already imported</dt><dd>{result.alreadyImported}</dd>
            <dt className="text-gray-500">Nothing to change</dt><dd>{result.noEffect}</dd>
          </dl>
          {result.changed > 0 && (
            <p className="mt-2 text-sm text-amber-700">
              {plural(result.changed, "row")} changed in Splitwise since the last import. To take the new numbers, undo that import below and import again.
            </p>
          )}
          {result.unreadableRows > 0 && <p className="mt-2 text-sm text-amber-700">{plural(result.unreadableRows, "row")} couldn&apos;t be read and were skipped.</p>}
          {result.suggested + result.unmatched > 0 && (
            <Link href="/review" className="mt-3 inline-block text-sm text-blue-600 hover:underline">Review the matches →</Link>
          )}
        </section>
      )}

      <PastImports />
    </section>
  );
}

/** Each import can be undone as a whole: every share, settlement and owed spend it made is reversed. */
function PastImports() {
  const client = useQueryClient();
  const list = useQuery({ queryKey: ["splitwise", "imports"], queryFn: () => getData<SplitImportItem[]>("/api/v1/splitwise/imports") });
  const [asking, setAsking] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const undo = async (id: string) => {
    setAsking(null); setFailure(null);
    try { await deleteData(`/api/v1/splitwise/imports/${id}`); }
    catch (e) { setFailure(errorMessage(e)); }
    finally { invalidate(client); }
  };

  if (list.isPending) return null;
  if (list.error) return <p role="alert" className="mt-3 text-sm text-red-600">{errorMessage(list.error)}</p>;
  if (!list.data?.length) return null;
  return (
    <section aria-label="Past Splitwise imports" className="mt-4">
      <h3 className="text-sm font-semibold text-gray-700">Past imports</h3>
      {failure && <p role="alert" className="text-sm text-red-600">{failure}</p>}
      <ul className="mt-1 space-y-1 text-sm">
        {list.data.map((i) => (
          <li key={i.id} className="flex items-center justify-between gap-2">
            <span>{new Date(i.createdAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })} · {plural(i.rows, "row")}</span>
            {asking === i.id ? (
              <span className="flex gap-2">
                <button type="button" onClick={() => undo(i.id)} className="text-red-700 underline">Yes, undo it</button>
                <button type="button" onClick={() => setAsking(null)} className="underline">Keep</button>
              </span>
            ) : (
              <button type="button" onClick={() => setAsking(i.id)} className="text-blue-600 underline">Undo import</button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
