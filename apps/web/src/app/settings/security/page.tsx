"use client";

import Link from "next/link";
import AppShell from "@/components/AppShell";
import SecuritySessions from "@/components/SecuritySessions";

/** Settings → Security (AOS-620): sessions, sign out one or everywhere. */
export default function SecurityPage() {
  return (
    <AppShell>
      <div className="space-y-6">
        <Link href="/settings" className="inline-flex min-h-11 items-center pr-3 text-sm text-accent">← Settings</Link>
        <h2 className="text-xl font-bold">Security</h2>
        <SecuritySessions />
      </div>
    </AppShell>
  );
}
