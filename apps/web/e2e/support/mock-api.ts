import type { Page, Request, Route } from "@playwright/test";
import { fx } from "../../test/fixtures";

type Reply = { status?: number; data?: unknown; error?: { code: string; message: string }; pagination?: unknown; delayMs?: number };
type Handler = (req: Request, url: URL) => Reply | Promise<Reply>;

const meta = { requestId: "req-e2e", timestamp: "2026-10-16T06:30:00Z" };

/**
 * Answers the app's /api/v1 calls inside the browser (AOS-311 mocked mode). Defaults come from the
 * shared fixtures; a test overrides any endpoint with `api.on("GET /api/v1/x", handler)` and can read
 * `api.calls` to assert what the app sent. Unknown endpoints fail loudly (501) so gaps are visible.
 */
export class MockApi {
  readonly calls: { method: string; path: string; body: unknown }[] = [];
  private readonly handlers = new Map<string, Handler>();

  constructor(private readonly page: Page) {
    const opens = [fx.anomaly(), fx.anomaly({ id: "an-2", type: "odd_hours", reason: "Paid at 3am", agentExplanation: null })];
    this.on("GET /api/v1/dashboard/home", () => ({ data: fx.home() }))
      .on("GET /api/v1/bills/upcoming", () => ({ data: [fx.bill(), fx.bill({ id: "b-2", billerName: "BESCOM", amount: "1840", billType: "electricity", dueDate: "2026-10-28" })] }))
      .on("GET /api/v1/briefings/latest", () => ({ data: fx.briefing() }))
      .on("GET /api/v1/briefings", () => ({ data: [fx.briefing(), fx.briefing({ id: "br-0", periodStart: "2026-09-28", periodEnd: "2026-10-04", content: "Quiet week." })] }))
      .on("GET /api/v1/subscriptions/summary", () => ({ data: fx.subscriptionSummary() }))
      .on("GET /api/v1/subscriptions", (_r, u) => ({ data: u.searchParams.get("flagged") ? [fx.subscription({ id: "s-9", name: "Gym", isFlagged: true, flaggedReason: "No visits in 45 days" })] : [fx.subscription()] }))
      .on("GET /api/v1/transactions", (_r, u) => u.searchParams.get("cursor")
        ? { data: [fx.transaction({ id: "t-3", merchantName: "Zepto" })], pagination: { cursor: null, hasMore: false, total: 3 } }
        : { data: [fx.transaction(), fx.transaction({ id: "t-2", merchantName: "Amazon", category: "shopping", amount: "999" })], pagination: { cursor: "p2", hasMore: true, total: 3 } })
      .on("GET /api/v1/anomalies", () => ({ data: opens }))
      .on("GET /api/v1/anomalies/:id", (_r, u) => { const a = opens.find((x) => u.pathname.endsWith(x.id)); return a ? { data: a } : { status: 404, error: { code: "NOT_FOUND", message: "Anomaly not found" } }; })
      .on("PATCH /api/v1/anomalies/:id", (_r, u) => { const i = opens.findIndex((x) => u.pathname.endsWith(x.id)); if (i >= 0) opens.splice(i, 1); return { data: {} }; })
      .on("GET /api/v1/connections", () => ({ data: [fx.connection()] }))
      .on("GET /api/v1/users/me/notification-preferences", () => ({ data: fx.prefs() }))
      .on("PATCH /api/v1/users/me/notification-preferences", () => ({ data: {} }))
      .on("GET /api/v1/auth/sessions", () => ({ data: [fx.session()] }))
      .on("GET /api/v1/device-tokens", () => ({ data: [] }))
      .on("POST /api/v1/transactions/cash", () => ({ status: 202, data: { saved: true, duplicate: false } }))
      .on("POST /api/v1/auth/refresh", () => ({ data: {} }))
      .on("POST /api/v1/auth/logout", () => ({ data: {} }))
      .on("GET /api/v1/transactions/suggestions", () => ({ data: [] }))
      .on("GET /api/v1/push", () => ({ data: { enabled: false, publicKey: null, devices: [] } }))
      .on("GET /api/v1/dues", (_r, u) => {
        const m = u.searchParams.get("month");
        return { data: m && m !== "2026-10" ? fx.duesMonth({ month: m, items: [], totals: { open: "0.00", paid: "0.00", renewals: "0.00" } }) : fx.duesMonth() };
      })
      .on("GET /api/v1/analytics/summary", (_r, u) => ({ data: { ...fx.summary(), period: { from: u.searchParams.get("from"), to: u.searchParams.get("to") } } }))
      .on("GET /api/v1/analytics/series", () => ({ data: fx.week() }))
      .on("GET /api/v1/analytics/categories", () => ({ data: fx.categories() }))
      .on("GET /api/v1/analytics/merchants", () => ({ data: fx.merchants() }));
  }

  on(key: string, handler: Handler): this {
    this.handlers.set(key, handler);
    return this;
  }

  /** Every call so far to METHOD path (exact path, ignoring the query). */
  callsTo(method: string, path: string) {
    return this.calls.filter((c) => c.method === method && c.path === path);
  }

  async install() {
    await this.page.route("**/api/v1/**", (route) => this.handle(route));
    return this;
  }

  private match(method: string, path: string): Handler | undefined {
    const exact = this.handlers.get(`${method} ${path}`);
    if (exact) return exact;
    for (const [key, h] of this.handlers) {
      const [m, pattern] = key.split(" ");
      if (m !== method || !pattern.includes(":")) continue;
      const re = new RegExp("^" + pattern.replace(/:[^/]+/g, "[^/]+") + "$");
      if (re.test(path)) return h;
    }
    return undefined;
  }

  private async handle(route: Route) {
    const req = route.request();
    const url = new URL(req.url());
    this.calls.push({ method: req.method(), path: url.pathname, body: safe(() => req.postDataJSON()) });
    const handler = this.match(req.method(), url.pathname);
    if (!handler) return route.fulfill({ status: 501, json: { success: false, data: null, error: { code: "E2E_UNMOCKED", message: `${req.method()} ${url.pathname} is not mocked` }, meta } });
    const r = await handler(req, url);
    if (r.delayMs) await new Promise((res) => setTimeout(res, r.delayMs));
    const ok = !r.error;
    await route.fulfill({
      status: r.status ?? (ok ? 200 : 400),
      json: { success: ok, data: ok ? (r.data ?? null) : null, error: r.error ?? null, meta, ...(r.pagination ? { pagination: r.pagination } : {}) },
    });
  }
}

function safe<T>(f: () => T): T | null {
  try { return f(); } catch { return null; }
}
