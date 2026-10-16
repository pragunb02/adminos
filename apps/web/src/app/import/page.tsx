"use client";

import Link from "next/link";
import { useState } from "react";
import AppShell from "@/components/AppShell";
import { api } from "@/lib/api";

interface ImportResult {
  bankCode: string | null;
  accountLast4: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  rows: number;
  accepted: number;
  duplicates: number;
  skippedRows: number;
  warnings: string[];
}

const BANKS: Record<string, string> = {
  hdfc: "HDFC Bank", sbi: "State Bank of India", icici: "ICICI Bank", axis: "Axis Bank", kotak: "Kotak Mahindra Bank",
  yes: "Yes Bank", indusind: "IndusInd Bank", pnb: "Punjab National Bank", bob: "Bank of Baroda", idfc: "IDFC FIRST Bank",
};

/**
 * Import a bank statement (AOS-202). The file and password are used once to read the transactions and
 * are not kept (D-37). Re-importing the same or an overlapping statement only adds new transactions.
 */
export default function ImportPage() {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState("");
  const [needsPassword, setNeedsPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setBusy(true);
    setError(null);
    setResult(null);
    const form = new FormData();
    form.append("file", file);
    if (password) form.append("password", password);
    const res = await api.upload<ImportResult>("/api/v1/statements", form);
    setBusy(false);
    if (res.success && res.data) {
      setResult(res.data);
      setPassword("");
      setNeedsPassword(false);
      return;
    }
    const code = res.error?.code;
    if (code === "PASSWORD_REQUIRED" || code === "PASSWORD_INCORRECT") setNeedsPassword(true);
    setError(
      code === "PASSWORD_REQUIRED" ? "This statement is password-protected. Enter its password (often your date of birth or customer ID)."
      : code === "PASSWORD_INCORRECT" ? "That password didn't work. Try again."
      : res.error?.message ?? "The statement couldn't be imported.",
    );
  };

  return (
    <AppShell>
      <h2 className="text-xl font-bold mb-1">Import a bank statement</h2>
      <p className="text-sm text-gray-500 mb-4">
        Download a statement from your net banking (PDF, CSV or Excel) and upload it. Months of transactions appear at once.
        The file and password are used once and not kept; importing the same months again won&apos;t create duplicates.
      </p>

      <form onSubmit={submit} className="bg-white border border-gray-200 rounded-lg p-4 space-y-3">
        <input
          type="file"
          aria-label="Statement file"
          accept=".pdf,.csv,.xls,.xlsx"
          onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null); setError(null); }}
          className="block w-full text-sm"
        />
        {(needsPassword || file?.name.toLowerCase().endsWith(".pdf")) && (
          <input
            type="password"
            autoComplete="off"
            aria-label="Statement password"
            placeholder="PDF password (if the statement has one)"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="block w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm"
          />
        )}
        <button type="submit" disabled={!file || busy} className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50">
          {busy ? "Importing…" : "Import"}
        </button>
        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      </form>

      {result && (
        <section role="status" className="mt-4 bg-white border border-green-200 rounded-lg p-4">
          <h3 className="font-semibold text-green-800">
            {result.accepted} new transaction{result.accepted === 1 ? "" : "s"} imported
          </h3>
          <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm text-gray-700">
            <dt className="text-gray-500">Bank</dt><dd>{(result.bankCode && BANKS[result.bankCode]) ?? "Not recognised"}{result.accountLast4 ? ` ·· ${result.accountLast4}` : ""}</dd>
            <dt className="text-gray-500">Period</dt><dd>{result.periodStart} → {result.periodEnd}</dd>
            <dt className="text-gray-500">Already imported</dt><dd>{result.duplicates}</dd>
            <dt className="text-gray-500">Lines skipped</dt><dd>{result.skippedRows} (balances, totals, headers)</dd>
          </dl>
          {result.warnings.length > 0 && (
            <ul className="mt-2 text-sm text-amber-700 list-disc pl-5">{result.warnings.map((w) => <li key={w}>{w}</li>)}</ul>
          )}
          <Link href="/transactions" className="mt-3 inline-block text-sm text-blue-600 hover:underline">See transactions →</Link>
        </section>
      )}
    </AppShell>
  );
}
