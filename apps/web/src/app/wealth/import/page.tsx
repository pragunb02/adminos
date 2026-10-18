"use client";

import Link from "next/link";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import Section, { Skeleton } from "@/components/console/Section";
import { BlockedList } from "@/components/wealth/NeedsReview";
import { api } from "@/lib/api";
import { errorMessage, getData, postData } from "@/lib/query";
import { CAS_UPLOAD_PATH, dayLabel, IMPORTS_PATH, importError, undoPath, type CasImportResult, type ImportBatch } from "@/lib/wealth";

/**
 * Import a consolidated account statement (AOS-805/820): a CAMS or KFintech "Detailed" CAS. The file and password are
 * used once and never kept (D-37); every holding must add up to the statement's units or it waits in Review; an
 * import can be undone.
 */
export default function WealthImportPage() {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<CasImportResult | null>(null);
  const imports = useQuery({ queryKey: ["wealth", "imports"], queryFn: () => getData<{ imports: ImportBatch[] }>(IMPORTS_PATH) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["wealth"] });
  const undo = useMutation({ mutationFn: (id: string) => postData(undoPath(id), {}), onSuccess: refresh });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || !password) return;
    setBusy(true); setError(null); setResult(null);
    const form = new FormData();
    form.append("file", file);
    form.append("password", password);
    const res = await api.upload<CasImportResult>(CAS_UPLOAD_PATH, form);
    setBusy(false);
    setPassword("");
    if (res.success && res.data) {
      setResult(res.data);
      refresh();
      return;
    }
    setError(importError(res.error?.code, res.error?.message));
  };

  const reconciled = result ? result.holdings.length - result.blocked : 0;
  return (
    <AppShell>
      <h2 className="mb-1 text-xl font-bold">Import a mutual fund statement</h2>
      <p className="mb-4 text-sm text-ink-muted">
        Request a <strong>Detailed</strong> consolidated account statement from CAMS or KFintech (from your first investment), with a password
        you choose. Upload it here: the file and password are used once and not kept. Importing it again adds nothing new.
      </p>
      <form onSubmit={submit} className="space-y-3 rounded-xl border border-line bg-surface p-4">
        <input type="file" aria-label="Statement PDF" accept=".pdf,application/pdf" className="block w-full text-sm"
          onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); setError(null); }} />
        <input type="password" autoComplete="off" aria-label="Statement password" placeholder="The password you chose for the statement"
          value={password} onChange={(e) => setPassword(e.target.value)} className="block w-full rounded-lg border border-line px-3 py-2 text-sm" />
        <button type="submit" disabled={!file || !password || busy} className="h-11 rounded-lg bg-accent px-4 text-sm font-semibold text-white disabled:opacity-50">
          {busy ? "Importing…" : "Import"}
        </button>
        {error && <p role="alert" className="text-sm text-negative">{error}</p>}
      </form>

      {result && (
        <section role="status" aria-label="Import result" className="mt-4 rounded-xl border border-line bg-surface p-4 text-sm">
          <h3 className="font-semibold">
            {result.batch.added} new transaction{result.batch.added === 1 ? "" : "s"} · {reconciled} holding{reconciled === 1 ? "" : "s"} add up
            {result.blocked > 0 && ` · ${result.blocked} need${result.blocked === 1 ? "s" : ""} review`}
          </h3>
          {result.batch.duplicates > 0 && <p className="text-ink-muted">{result.batch.duplicates} already imported earlier.</p>}
          {result.blocked > 0 && <div className="mt-2"><BlockedList items={result.holdings.filter((h) => h.status === "blocked")} /></div>}
          <Link href="/wealth" className="mt-3 inline-block text-accent underline">See your holdings</Link>
        </section>
      )}

      <div className="mt-4">
        <Section title="Your imports" error={imports.error ? errorMessage(imports.error) : null} onRetry={() => imports.refetch()}>
          {imports.isPending ? <Skeleton lines={2} /> : imports.data && imports.data.imports.length === 0 ? (
            <p className="text-sm text-ink-muted">Nothing imported yet.</p>
          ) : (
            <ul className="divide-y divide-line">
              {imports.data?.imports.map((b) => (
                <li key={b.id} className="flex items-center justify-between gap-2 py-2 text-sm">
                  <span>
                    {b.statementFrom && b.statementTo ? `${dayLabel(b.statementFrom)} – ${dayLabel(b.statementTo)}` : "Statement"} · {b.added} added
                    {b.status === "undone" && <span className="ml-1 text-ink-muted">(undone)</span>}
                  </span>
                  {b.status === "applied" && (
                    <button type="button" disabled={undo.isPending} onClick={() => undo.mutate(b.id)} className="h-11 rounded-lg border border-line px-3"
                      aria-label={`Undo the import of ${b.statementTo ? dayLabel(b.statementTo) : "this statement"}`}>Undo</button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {undo.error && <p role="alert" className="mt-2 text-sm text-negative">{errorMessage(undo.error)}</p>}
        </Section>
      </div>
    </AppShell>
  );
}
