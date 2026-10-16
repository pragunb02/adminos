"use client";

import CashQuickAdd from "./CashQuickAdd";
import { Rail, TabBar } from "./Nav";

/**
 * The console frame (AOS-302, D-49): a left rail on desktop (≥ 1024px) and a 5-tab bar on smaller
 * screens. One navigation is rendered per breakpoint (the other is display:none, so not in the a11y tree).
 */
export default function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-page text-ink lg:flex">
      <div className="hidden lg:block">
        <Rail />
      </div>
      <div className="min-w-0 flex-1">
        <header className="flex items-center justify-between border-b border-line bg-surface px-4 py-3 lg:hidden">
          <h1 className="text-lg font-bold">AdminOS</h1>
        </header>
        <main className="mx-auto w-full max-w-[1120px] px-4 py-6 pb-24 lg:px-6 lg:pb-8">{children}</main>
      </div>
      <div className="lg:hidden">
        <TabBar />
      </div>
      <CashQuickAdd />
    </div>
  );
}
