"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { getData } from "@/lib/query";

/** The 5 destinations (D-49): a bottom tab bar on phones, a rail on desktop. */
export const DESTINATIONS = [
  { href: "/", label: "Home", icon: "◉" },
  { href: "/spending", label: "Spending", icon: "▤" },
  { href: "/transactions", label: "Activity", icon: "≡" },
  { href: "/review", label: "Review", icon: "⚑" },
  { href: "/more", label: "More", icon: "⋯" },
] as const;

/** What lives under More (expanded in the desktop rail). */
export const MORE_LINKS = [
  { href: "/leaks", label: "Where it leaks" },
  { href: "/ask", label: "Ask your money" },
  { href: "/import", label: "Import statement" },
  { href: "/bills", label: "Bills" },
  { href: "/subscriptions", label: "Subscriptions" },
  { href: "/documents", label: "Documents" },
  { href: "/briefings", label: "Briefings" },
  { href: "/settings", label: "Settings" },
] as const;

/** Which destination a path belongs to: More owns its sub-pages and anything unknown. */
export function destinationOf(pathname: string): string {
  if (pathname === "/") return "/";
  const top = DESTINATIONS.find((d) => d.href !== "/" && (pathname === d.href || pathname.startsWith(`${d.href}/`)));
  return top?.href ?? "/more";
}

/** How many suggestions wait for review (they change the totals). Quiet on failure: the badge is optional. */
function useReviewCount(): number {
  const { data } = useQuery({
    queryKey: ["suggestions", "count"],
    queryFn: () => getData<unknown[]>("/api/v1/transactions/suggestions"),
    staleTime: 60_000,
  });
  return data?.length ?? 0;
}

function Badge({ count }: { count: number }) {
  if (count === 0) return null;
  return (
    <span className="ml-1 min-w-5 rounded-full bg-accent px-1.5 text-center text-[11px] font-semibold leading-5 text-white" aria-label={`${count} to review`}>
      {count > 99 ? "99+" : count}
    </span>
  );
}

/** Phones: 5 equal tabs pinned to the bottom (every one always reachable, AOS-311). */
export function TabBar() {
  const current = destinationOf(usePathname());
  const reviews = useReviewCount();
  return (
    <nav aria-label="Main navigation" className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-surface pb-[env(safe-area-inset-bottom)]">
      <ul className="mx-auto grid max-w-md grid-cols-5">
        {DESTINATIONS.map((d) => (
          <li key={d.href}>
            <Link
              href={d.href}
              aria-current={current === d.href ? "page" : undefined}
              className={`flex h-14 flex-col items-center justify-center text-[11px] ${current === d.href ? "font-semibold text-accent" : "text-ink-muted"}`}
            >
              <span aria-hidden className="text-base leading-none">{d.icon}</span>
              <span className="mt-1 flex items-center">
                {d.label}
                {d.href === "/review" && <Badge count={reviews} />}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/** Desktop: a left rail with the same destinations, More expanded, and Log out. */
export function Rail() {
  const pathname = usePathname();
  const current = destinationOf(pathname);
  const reviews = useReviewCount();
  const link = (active: boolean) =>
    `flex items-center rounded-lg px-3 py-2 text-sm ${active ? "bg-blue-50 font-semibold text-accent" : "text-ink-muted hover:bg-page hover:text-ink"}`;
  return (
    <nav aria-label="Main navigation" className="sticky top-0 flex h-screen w-56 shrink-0 flex-col gap-1 border-r border-line bg-surface p-3">
      <p className="px-3 pb-3 pt-1 text-lg font-bold text-ink">AdminOS</p>
      {DESTINATIONS.filter((d) => d.href !== "/more").map((d) => (
        <Link key={d.href} href={d.href} aria-current={current === d.href ? "page" : undefined} className={link(current === d.href)}>
          {d.label}
          {d.href === "/review" && <Badge count={reviews} />}
        </Link>
      ))}
      <p className="mt-4 px-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">More</p>
      {MORE_LINKS.map((m) => (
        <Link key={m.href} href={m.href} aria-current={pathname === m.href ? "page" : undefined} className={link(pathname === m.href)}>
          {m.label}
        </Link>
      ))}
      <button type="button" onClick={() => api.logout()} className="mt-auto rounded-lg px-3 py-2 text-left text-sm text-ink-muted hover:bg-page hover:text-ink">
        Log out
      </button>
    </nav>
  );
}
