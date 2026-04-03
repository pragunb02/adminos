"use client";

import NavBar from "./NavBar";

export default function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-white border-b border-gray-200 px-4 py-3 flex items-center justify-between">
        <h1 className="text-lg font-bold text-gray-900">AdminOS</h1>
      </header>
      <div className="hidden sm:block">
        <NavBar />
      </div>
      <main className="flex-1 max-w-4xl w-full mx-auto px-4 py-6 pb-20 sm:pb-6">
        {children}
      </main>
      <div className="sm:hidden">
        <NavBar />
      </div>
    </div>
  );
}
