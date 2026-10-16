"use client";

import Link from "next/link";
import AppShell from "@/components/AppShell";
import { MORE_LINKS } from "@/components/Nav";
import { api } from "@/lib/api";

/** Everything that isn't one of the four main destinations (D-49). */
export default function MorePage() {
  return (
    <AppShell>
      <h2 className="mb-4 text-xl font-bold">More</h2>
      <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
        {MORE_LINKS.map((m) => (
          <li key={m.href}>
            <Link href={m.href} className="flex items-center justify-between px-4 py-3 text-sm hover:bg-page">
              {m.label}
              <span aria-hidden className="text-ink-muted">›</span>
            </Link>
          </li>
        ))}
        <li>
          <button type="button" onClick={() => api.logout()} className="w-full px-4 py-3 text-left text-sm text-negative hover:bg-page">
            Log out
          </button>
        </li>
      </ul>
    </AppShell>
  );
}
