"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/api";

export interface WhatsAppImportResult {
  messages: number;
  fromBanks: number;
  accepted: number;
  duplicates: number;
  mandates: number;
  ignored: Record<string, number>;
  unreadableLines: number;
  /** Bank messages no parser recognised, queued for the AI reader; they wait in Review (AOS-705). Absent on older APIs. */
  reading?: number;
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;

/**
 * Import bank messages from a WhatsApp chat export (AOS-403). Only messages from bank senders are read;
 * the chat is used once and not kept (D-33). Importing the same chat again adds only new messages.
 */
export default function WhatsAppImport() {
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<WhatsAppImportResult | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setBusy(true);
    setError(null);
    setResult(null);
    const form = new FormData();
    form.append("file", file);
    const res = await api.upload<WhatsAppImportResult>("/api/v1/imports/whatsapp", form);
    setBusy(false);
    if (res.success && res.data) setResult(res.data);
    else setError(res.error?.message ?? "The chat couldn't be imported.");
  };

  const ignored = result ? Object.entries(result.ignored) : [];

  return (
    <section aria-labelledby="whatsapp-import" className="mt-8">
      <h2 id="whatsapp-import" className="text-xl font-bold mb-1">Import a WhatsApp bank chat</h2>
      <p className="text-sm text-gray-500 mb-2">
        If your bank sends alerts on WhatsApp, export that chat and upload it. Only messages from banks are read;
        chats with people are never used. The file is used once and not kept.
      </p>
      <details className="mb-3 text-sm text-gray-600">
        <summary className="cursor-pointer text-blue-600">How to export a chat</summary>
        <ol className="mt-1 list-decimal pl-5 space-y-0.5">
          <li>Open the bank&apos;s chat in WhatsApp and tap its name at the top.</li>
          <li>Choose <strong>Export chat</strong>, then <strong>Without media</strong>.</li>
          <li>Save the file (a .zip on iPhone, a .txt on Android) and choose it here.</li>
        </ol>
      </details>

      <form onSubmit={submit} className="bg-white border border-gray-200 rounded-lg p-4 space-y-3">
        <input
          type="file"
          aria-label="WhatsApp chat export"
          accept=".txt,.zip"
          onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); setError(null); }}
          className="block w-full text-sm"
        />
        <button type="submit" disabled={!file || busy} className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50">
          {busy ? "Importing…" : "Import chat"}
        </button>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      </form>

      {result && (
        <section role="status" aria-label="WhatsApp import result" className="mt-4 bg-white border border-green-200 rounded-lg p-4">
          <h3 className="font-semibold text-green-800">{plural(result.accepted, "new transaction")} imported</h3>
          {result.fromBanks === 0 ? (
            <p className="mt-2 text-sm text-amber-700">
              None of the {plural(result.messages, "message")} came from a bank. Export the chat with your bank&apos;s WhatsApp account.
            </p>
          ) : (
            <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm text-gray-700">
              <dt className="text-gray-500">Bank messages</dt><dd>{result.fromBanks} of {result.messages}</dd>
              <dt className="text-gray-500">Already imported</dt><dd>{result.duplicates}</dd>
              <dt className="text-gray-500">Mandate updates</dt><dd>{result.mandates}</dd>
            </dl>
          )}
          {ignored.length > 0 && (
            <ul aria-label="Skipped messages" className="mt-2 text-sm text-gray-600 list-disc pl-5">
              {ignored.map(([reason, n]) => <li key={reason}>{plural(n, "message")} skipped: {reason}</li>)}
            </ul>
          )}
          {(result.reading ?? 0) > 0 && (
            <p className="mt-2 text-sm text-gray-700">
              {plural(result.reading ?? 0, "message")} will be read by AI and wait for you in <Link href="/review" className="text-blue-600 hover:underline">Review</Link>.
            </p>
          )}
          {result.unreadableLines > 0 && (
            <p className="mt-2 text-sm text-amber-700">{plural(result.unreadableLines, "line")} had a date that couldn&apos;t be read.</p>
          )}
          <Link href="/transactions" className="mt-3 inline-block text-sm text-blue-600 hover:underline">See transactions →</Link>
        </section>
      )}
    </section>
  );
}
