import { HttpResponse } from "msw";

/** The API's response envelope (see services/api ApiResponse). Shared by unit tests and Playwright (AOS-311). */
export const meta = { requestId: "req-test", timestamp: "2026-10-16T06:30:00Z" };

export const ok = <T>(data: T, pagination?: { cursor: string | null; hasMore: boolean; total: number }) =>
  HttpResponse.json({ success: true, data, error: null, meta, ...(pagination ? { pagination } : {}) });

export const fail = (status: number, code: string, message: string) =>
  HttpResponse.json({ success: false, data: null, error: { code, message }, meta }, { status });
