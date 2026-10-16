import type { AnchorHTMLAttributes, ReactNode } from "react";
import { vi } from "vitest";

/**
 * A controllable stand-in for next/navigation in unit tests: set the path, params and query a page
 * sees, and assert where it navigated. Real browser history is covered by Playwright (AOS-311).
 */
export const nav = {
  pathname: "/",
  params: {} as Record<string, string>,
  search: new URLSearchParams(),
  router: { push: vi.fn(), replace: vi.fn(), back: vi.fn(), forward: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() },
  reset() {
    this.pathname = "/";
    this.params = {};
    this.search = new URLSearchParams();
    Object.values(this.router).forEach((f) => f.mockReset());
  },
};

export const navigationMock = {
  useRouter: () => nav.router,
  usePathname: () => nav.pathname,
  useParams: () => nav.params,
  useSearchParams: () => nav.search,
};

function Link({ href, children, ...rest }: { href: string; children: ReactNode } & AnchorHTMLAttributes<HTMLAnchorElement>) {
  return (
    <a href={href} {...rest}>
      {children}
    </a>
  );
}
export const linkMock = { default: Link };
