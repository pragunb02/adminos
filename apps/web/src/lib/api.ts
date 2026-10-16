/**
 * API client for AdminOS backend.
 * Handles standard envelope parsing and error handling.
 * Calls are same-origin (`/api/*` is proxied to the API by next.config.js, D-32), so the
 * HttpOnly JWT cookie is first-party and sent automatically. No manual token injection.
 *
 * OWASP Session Management: "Set the HttpOnly attribute for cookies
 * containing session tokens."
 */

import type { ApiResponse } from "./types";


class ApiClient {
  private baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  /** One refresh at a time: concurrent 401s wait for the same refresh call. */
  private refreshing: Promise<boolean> | null = null;

  /**
   * Sends a request. On 401 (access JWT expired), refreshes the session once using the HttpOnly
   * refresh cookie and retries. If the refresh fails, the session is over: go to /login (AOS-109).
   */
  async request<T>(path: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
    const first = await this.send<T>(path, options);
    if (first.status !== 401 || path.startsWith("/api/v1/auth/")) return first.body;

    if (await this.refreshSession()) {
      const retry = await this.send<T>(path, options);
      if (retry.status !== 401) return retry.body;
    }
    if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
      window.location.assign("/login");
    }
    return first.body;
  }

  private refreshSession(): Promise<boolean> {
    this.refreshing ??= fetch(`${this.baseUrl}/api/v1/auth/refresh`, { method: "POST", credentials: "same-origin" })
      .then((r) => r.ok)
      .catch(() => false)
      .finally(() => {
        this.refreshing = null;
      });
    return this.refreshing;
  }

  async logout(): Promise<void> {
    await fetch(`${this.baseUrl}/api/v1/auth/logout`, { method: "POST", credentials: "same-origin" }).catch(() => undefined);
    window.location.assign("/login");
  }

  private async send<T>(path: string, options: RequestInit): Promise<{ status: number; body: ApiResponse<T> }> {
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      ...(options.headers as Record<string, string>),
    };

    const response = await fetch(`${this.baseUrl}${path}`, {
      ...options,
      headers,
      credentials: "same-origin",
    });

    // Always resolve to the envelope, even for empty or non-JSON bodies (e.g. a proxy error page).
    const text = await response.text();
    try {
      return { status: response.status, body: JSON.parse(text) as ApiResponse<T> };
    } catch {
      const body: ApiResponse<T> = {
        success: false,
        data: null,
        error: {
          code: response.status === 401 ? "AUTH_002" : `HTTP_${response.status}`,
          message: response.statusText || "Unexpected response from the server",
        },
        meta: { requestId: response.headers.get("x-request-id") ?? "", timestamp: new Date().toISOString() },
      };
      return { status: response.status, body };
    }
  }

  get<T>(path: string) {
    return this.request<T>(path, { method: "GET" });
  }

  post<T>(path: string, body: unknown) {
    return this.request<T>(path, { method: "POST", body: JSON.stringify(body) });
  }

  patch<T>(path: string, body: unknown) {
    return this.request<T>(path, { method: "PATCH", body: JSON.stringify(body) });
  }

  delete<T>(path: string) {
    return this.request<T>(path, { method: "DELETE" });
  }
}

export const api = new ApiClient("");
