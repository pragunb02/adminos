"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { api } from "@/lib/api";

const links = [
  { href: "/", label: "Home", icon: "🏠" },
  { href: "/transactions", label: "Transactions", icon: "💳" },
  { href: "/import", label: "Import", icon: "📥" },
  { href: "/subscriptions", label: "Subscriptions", icon: "🔄" },
  { href: "/bills", label: "Bills", icon: "📄" },
  { href: "/briefings", label: "Briefings", icon: "📊" },
  { href: "/settings", label: "Settings", icon: "⚙️" },
];

export default function NavBar() {
  const pathname = usePathname();

  return (
    <nav className="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-200 z-50 sm:static sm:border-t-0 sm:border-b" aria-label="Main navigation">
      {/* 8 items don't fit a phone: the bar scrolls sideways so every one stays reachable (AOS-311; the redesign cuts it to 5, AOS-302). */}
      <div className="max-w-4xl mx-auto flex justify-between sm:justify-start gap-1 px-2 py-1 overflow-x-auto whitespace-nowrap">
        {links.map((link) => {
          const isActive = pathname === link.href;
          return (
            <Link
              key={link.href}
              href={link.href}
              aria-label={link.label}
              aria-current={isActive ? "page" : undefined}
              className={`shrink-0 flex flex-col sm:flex-row items-center gap-0.5 sm:gap-1.5 px-2 sm:px-3 py-2 rounded-lg text-xs sm:text-sm transition-colors ${
                isActive
                  ? "text-blue-600 bg-blue-50 font-medium"
                  : "text-gray-500 hover:text-gray-700 hover:bg-gray-50"
              }`}
            >
              <span className="text-base sm:text-sm">{link.icon}</span>
              <span className="text-[10px] sm:text-sm">{link.label}</span>
            </Link>
          );
        })}
        <button
          type="button"
          onClick={() => api.logout()}
          aria-label="Log out"
          className="shrink-0 flex flex-col sm:flex-row items-center gap-0.5 sm:gap-1.5 px-2 sm:px-3 py-2 rounded-lg text-xs sm:text-sm text-gray-500 hover:text-gray-700 hover:bg-gray-50 transition-colors sm:ml-auto"
        >
          <span className="text-base sm:text-sm">🚪</span>
          <span className="text-[10px] sm:text-sm">Log out</span>
        </button>
      </div>
    </nav>
  );
}
