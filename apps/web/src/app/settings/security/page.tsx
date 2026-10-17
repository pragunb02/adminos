"use client";

import Link from "next/link";
import AppShell from "@/components/AppShell";
import SecuritySessions from "@/components/SecuritySessions";
import PasskeyManager from "@/components/PasskeyManager";

/** Settings → Security: passkeys and recovery codes (AOS-617); sessions, sign out one or everywhere (AOS-620). */
export default function SecurityPage() {
  return (
    <AppShell>
      <div className="space-y-6">
        <Link href="/settings" className="inline-flex min-h-11 items-center pr-3 text-sm text-accent">← Settings</Link>
        <h2 className="text-xl font-bold">Security</h2>
        <PasskeyManager />
        <SecuritySessions />
      </div>
    </AppShell>
  );
}
