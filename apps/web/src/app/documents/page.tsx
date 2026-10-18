"use client";

import { Suspense, useEffect, useRef, useState, type FormEvent } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import AppShell from "@/components/AppShell";
import { Skeleton } from "@/components/console/Section";
import UndoToast from "@/components/console/UndoToast";
import ReadFields, { shownValue } from "@/components/documents/ReadFields";
import { ApiError, deleteData, errorMessage, getData, patchData, uploadData } from "@/lib/query";
import type { DocumentCategory, VaultDocument } from "@/lib/types";

export const CATEGORIES: { value: DocumentCategory; label: string }[] = [
  { value: "identity", label: "Identity (PAN, Aadhaar, passport)" },
  { value: "tax", label: "Tax (Form 16, ITR)" },
  { value: "insurance", label: "Insurance" },
  { value: "bank", label: "Bank" },
  { value: "property", label: "Property" },
  { value: "vehicle", label: "Vehicle" },
  { value: "medical", label: "Medical" },
  { value: "warranty", label: "Warranty" },
  { value: "other", label: "Other" },
];
const SHORT: Record<DocumentCategory, string> = { identity: "Identity", tax: "Tax", insurance: "Insurance", bank: "Bank", property: "Property", vehicle: "Vehicle", medical: "Medical", warranty: "Warranty", other: "Other" };
const MAX_BYTES = 20 * 1024 * 1024;
const UNDO_MS = 5000;

export function size(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** "Expires in 20 days", "Expired", or null; from the browser's date (dates only, no money). */
export function expiry(on: string | null, today = new Date()): { text: string; late: boolean } | null {
  if (!on) return null;
  const local = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  const days = Math.round((Date.parse(`${on}T00:00:00Z`) - Date.parse(`${local}T00:00:00Z`)) / 86_400_000);
  if (days < 0) return { text: "Expired", late: true };
  if (days === 0) return { text: "Expires today", late: true };
  if (days <= 30) return { text: `Expires in ${days} day${days === 1 ? "" : "s"}`, late: true };
  return { text: `Expires ${new Date(`${on}T00:00:00Z`).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" })}`, late: false };
}

/** The confirmed fields of a document in one line (AOS-717): issuer · number's last 4 · amount. */
export function details(d: VaultDocument): string | null {
  const parts = [
    d.issuer ?? null,
    d.numberLast4 ? `No. •••• ${d.numberLast4}` : null,
    d.amount ? shownValue({ field: "amount", value: d.amount }) : null,
  ].filter((p): p is string => !!p);
  return parts.length ? parts.join(" · ") : null;
}

const uploadMessage =(e: unknown) =>
  e instanceof ApiError && e.code === "DOC_005" ? "The documents vault isn't set up on the server yet (Cloudflare R2 settings in .env)." : errorMessage(e, "The file couldn't be uploaded");

function Upload({ onDone }: { onDone: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [category, setCategory] = useState<DocumentCategory>("other");
  const [expiresOn, setExpiresOn] = useState("");
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!file) return;
    if (file.size > MAX_BYTES) { setFailure("A document can be at most 20 MB."); return; }
    setBusy(true); setFailure(null); setDone(null);
    const form = new FormData();
    form.append("file", file);
    form.append("category", category);
    if (title.trim()) form.append("title", title.trim());
    if (expiresOn) form.append("expiresOn", expiresOn);
    try {
      const saved = await uploadData<VaultDocument>("/api/v1/documents", form);
      setDone(`${saved?.title ?? "Document"} saved, encrypted.`);
      setFile(null); setTitle(""); setExpiresOn("");
      if (input.current) input.current.value = "";
      onDone();
    } catch (err) {
      setFailure(uploadMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} aria-label="Add a document" className="space-y-3 rounded-xl border border-line bg-surface p-4">
      <h3 className="font-semibold">Add a document</h3>
      <label className="block text-sm">
        File (PDF or photo, up to 20 MB)
        <input ref={input} type="file" accept=".pdf,image/png,image/jpeg,image/webp,image/heic,.heic"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="mt-1 block w-full text-sm" />
      </label>
      <div className="grid gap-3 sm:grid-cols-3">
        <label className="block text-sm">Title (optional)
          <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} className="mt-1 block w-full rounded-md border border-line px-2 py-2" />
        </label>
        <label className="block text-sm">Kind
          <select value={category} onChange={(e) => setCategory(e.target.value as DocumentCategory)} className="mt-1 block w-full rounded-md border border-line bg-surface px-2 py-2">
            {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
        </label>
        <label className="block text-sm">Expires on (optional)
          <input type="date" value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} className="mt-1 block w-full rounded-md border border-line px-2 py-2" />
        </label>
      </div>
      <button type="submit" disabled={!file || busy} className="min-h-11 rounded-md bg-accent px-4 text-sm font-semibold text-white disabled:opacity-60">
        {busy ? "Encrypting and uploading…" : "Upload"}
      </button>
      {done && <p role="status" className="text-sm">{done}</p>}
      {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}
    </form>
  );
}

function Edit({ doc, onClose }: { doc: VaultDocument; onClose: (changed: boolean) => void }) {
  const [title, setTitle] = useState(doc.title);
  const [category, setCategory] = useState<DocumentCategory>(doc.category);
  const [expiresOn, setExpiresOn] = useState(doc.expiresOn ?? "");
  const [failure, setFailure] = useState<string | null>(null);
  const save = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await patchData(`/api/v1/documents/${doc.id}`, { title, category, expiresOn });
      onClose(true);
    } catch (err) {
      setFailure(errorMessage(err));
    }
  };
  return (
    <form onSubmit={save} aria-label={`Edit ${doc.title}`} className="mt-2 grid gap-2 sm:grid-cols-4">
      <label className="text-xs">Title<input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} required className="mt-1 block w-full rounded-md border border-line px-2 py-1 text-sm" /></label>
      <label className="text-xs">Kind
        <select value={category} onChange={(e) => setCategory(e.target.value as DocumentCategory)} className="mt-1 block w-full rounded-md border border-line bg-surface px-2 py-1 text-sm">
          {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{SHORT[c.value]}</option>)}
        </select>
      </label>
      <label className="text-xs">Expires on<input type="date" value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} className="mt-1 block w-full rounded-md border border-line px-2 py-1 text-sm" /></label>
      <div className="flex items-end gap-2">
        <button type="submit" className="min-h-9 rounded-md bg-accent px-3 text-sm text-white">Save</button>
        <button type="button" onClick={() => onClose(false)} className="min-h-9 rounded-md border border-line px-3 text-sm">Cancel</button>
      </div>
      {failure && <p role="alert" className="text-sm text-negative sm:col-span-4">{failure}</p>}
    </form>
  );
}

function DocumentsContent() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const queryClient = useQueryClient();
  const category = (CATEGORIES.find((c) => c.value === params.get("category"))?.value ?? "") as DocumentCategory | "";
  const q = params.get("q") ?? "";
  const [search, setSearch] = useState(q);
  const [editing, setEditing] = useState<string | null>(null);
  const [pending, setPending] = useState<VaultDocument | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const pendingRef = useRef<VaultDocument | null>(null);
  useEffect(() => { pendingRef.current = pending; }, [pending]);

  const url = (next: { category?: string; q?: string }) => {
    const p = new URLSearchParams();
    const c = next.category ?? category; const s = next.q ?? q;
    if (c) p.set("category", c);
    if (s) p.set("q", s);
    return p.size ? `${pathname}?${p}` : pathname;
  };
  // Typing replaces the URL (Back skips the letters); choosing a kind pushes it (D-47).
  useEffect(() => {
    if (search === q) return;
    const t = setTimeout(() => router.replace(url({ q: search }), { scroll: false }), 300);
    return () => clearTimeout(t);
  });

  const list = useQuery({
    queryKey: ["documents", category, q],
    queryFn: () => getData<VaultDocument[]>(`/api/v1/documents?${new URLSearchParams({ ...(category ? { category } : {}), ...(q ? { q } : {}) })}`),
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["documents"] });

  const commitDelete = async (doc: VaultDocument) => {
    try {
      await deleteData(`/api/v1/documents/${doc.id}`);
    } catch (e) {
      setFailure(`${doc.title}: ${errorMessage(e)}`);
    } finally {
      refresh();
    }
  };
  // Leaving the page sends a pending delete (as on Review).
  useEffect(() => () => { if (pendingRef.current) void commitDelete(pendingRef.current); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const remove = (doc: VaultDocument) => {
    if (pending) void commitDelete(pending);
    setFailure(null);
    setPending(doc);
  };

  const visible = (list.data ?? []).filter((d) => d.id !== pending?.id);

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-xl font-bold">Documents</h2>
        <p className="text-sm text-ink-muted">Encrypted before they leave AdminOS; only you can open them.</p>
      </div>
      <Upload onDone={refresh} />
      <div className="flex flex-wrap gap-2">
        <label className="min-w-48 flex-1 text-sm">
          <span className="sr-only">Search documents</span>
          <input type="search" placeholder="Search title, notes or file name" value={search} onChange={(e) => setSearch(e.target.value)}
            className="block w-full rounded-md border border-line px-3 py-2" />
        </label>
        <label className="text-sm">
          <span className="sr-only">Kind</span>
          <select aria-label="Kind" value={category} onChange={(e) => router.push(url({ category: e.target.value }), { scroll: false })}
            className="rounded-md border border-line bg-surface px-2 py-2">
            <option value="">All kinds</option>
            {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{SHORT[c.value]}</option>)}
          </select>
        </label>
      </div>
      {failure && <p role="alert" className="text-sm text-negative">{failure}</p>}
      {list.isPending ? <Skeleton lines={4} /> : list.error ? (
        <p role="alert" className="text-sm text-negative">{errorMessage(list.error)} <button type="button" className="underline" onClick={() => list.refetch()}>Try again</button></p>
      ) : visible.length === 0 ? (
        <p className="rounded-xl border border-line bg-surface p-6 text-center text-sm text-ink-muted">
          {q || category ? "No documents match." : "No documents yet. Keep your PAN card, Form 16, insurance policies and warranties here."}
        </p>
      ) : (
        <ul className="divide-y divide-line rounded-xl border border-line bg-surface">
          {visible.map((d) => {
            const exp = expiry(d.expiresOn);
            return (
              <li key={d.id} aria-label={d.title} className="p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate font-medium">{d.title}</p>
                    <p className="text-xs text-ink-muted">{SHORT[d.category]} · {d.fileType.toUpperCase()} · {size(d.sizeBytes)}</p>
                    {exp && <p className={`text-xs ${exp.late ? "font-semibold text-negative" : "text-ink-muted"}`}>{exp.text}</p>}
                    {details(d) && <p className="text-xs text-ink-muted">{details(d)}</p>}
                  </div>
                  <div className="flex shrink-0 gap-2 text-sm">
                    <a href={`/api/v1/documents/${d.id}/file`} download className="underline">Download</a>
                    <button type="button" onClick={() => setEditing(editing === d.id ? null : d.id)} className="underline">Edit</button>
                    <button type="button" onClick={() => remove(d)} className="underline">Delete</button>
                  </div>
                </div>
                {editing === d.id && <Edit doc={d} onClose={(changed) => { setEditing(null); if (changed) refresh(); }} />}
                <ReadFields doc={d} onSaved={refresh} />
              </li>
            );
          })}
        </ul>
      )}
      {pending && (
        <UndoToast key={pending.id} message={`${pending.title} deleted`} ms={UNDO_MS}
          onUndo={() => setPending(null)}
          onClose={() => { const doc = pendingRef.current; setPending(null); if (doc) void commitDelete(doc); }} />
      )}
    </div>
  );
}

/** Documents vault (AOS-507): PAN, Form 16, policies… encrypted in Cloudflare R2. Filters live in the URL (D-47). */
export default function DocumentsPage() {
  return (
    <AppShell>
      <Suspense fallback={<Skeleton lines={4} />}>
        <DocumentsContent />
      </Suspense>
    </AppShell>
  );
}
