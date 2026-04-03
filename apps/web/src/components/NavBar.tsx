"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const links = [
  { href: "/", label: "Home", icon: "🏠" },
  { href: "/transactions", label: "Transactions", icon: "💳" },
  { href: "/subscriptions", label: "Subscriptions", icon: "🔄" },
  { href: "/bills", label: "Bills", icon: "📄" },
  { href: "/briefings", label: "Briefings", icon: "📊" },
  { href: "/settings", label: "Settings", icon: "⚙️" },
];

export default function NavBar() {
  const pathname = usePathname();

  return (
    <nav className="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-200 z-50 sm:static sm:border-t-0 sm:border-b" aria-label="Main navigation">
      <div className="max-w-4xl mx-auto flex justify-around sm:justify-start sm:gap-1 px-2 py-1">
        {links.map((link) => {
          const isActive = pathname === link.href;
          return (
            <Link
              key={link.href}
              href={link.href}
              aria-label={link.label}
              aria-current={isActive ? "page" : undefined}
              className={`flex flex-col sm:flex-row items-center gap-0.5 sm:gap-1.5 px-2 sm:px-3 py-2 rounded-lg text-xs sm:text-sm transition-colors ${
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
      </div>
    </nav>
  );
}
