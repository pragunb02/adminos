"use client";

import AppShell from "@/components/AppShell";
import BalancesCard from "@/components/balances/BalancesCard";

/** Bank balances (AOS-720): every account's latest known balance, and how it's chosen. */
export default function BalancesPage() {
  return (
    <AppShell>
      <h2 className="mb-4 text-xl font-bold">Bank balances</h2>
      <BalancesCard full />
    </AppShell>
  );
}
