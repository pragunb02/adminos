import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { api } from "./api";

const assign = vi.fn();
function atPath(path: string) {
  // jsdom's location can't be navigated; swap in a stub that records assign() calls.
  Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, pathname: path, origin: "http://localhost:3000", assign } });
}
afterEach(() => assign.mockReset());

describe("api client (AOS-109/308)", () => {
  it("returns the envelope on success and sends JSON same-origin", async () => {
    let seen: Request | undefined;
    server.use(http.post("/api/v1/things", async ({ request }) => { seen = request; return ok({ id: "1" }); }));
    const res = await api.post<{ id: string }>("/api/v1/things", { a: 1 });
    expect(res).toMatchObject({ success: true, data: { id: "1" } });
    expect(seen!.headers.get("content-type")).toBe("application/json");
    expect(await seen!.json()).toEqual({ a: 1 });
  });

  it("does not set a JSON content type for uploads", async () => {
    let type: string | null = "unset";
    server.use(http.post("/api/v1/statements", ({ request }) => { type = request.headers.get("content-type"); return ok({}); }));
    const form = new FormData();
    form.append("file", new Blob(["x"]), "s.csv");
    await api.upload("/api/v1/statements", form);
    expect(type).toMatch(/^multipart\/form-data; boundary=/);
  });

  it.each([
    ["patch", "PATCH"],
    ["delete", "DELETE"],
    ["get", "GET"],
  ] as const)("%s uses %s", async (fn, method) => {
    server.use(http.all("/api/v1/x", ({ request }) => ok({ method: request.method })));
    const res = fn === "patch" ? await api.patch("/api/v1/x", {}) : await api[fn]("/api/v1/x");
    expect(res.data).toEqual({ method });
  });

  it("turns an empty or non-JSON body into an error envelope", async () => {
    server.use(http.get("/api/v1/proxy-down", () => new HttpResponse("<html>Bad gateway</html>", { status: 502, statusText: "Bad Gateway", headers: { "x-request-id": "r-9" } })));
    const res = await api.get("/api/v1/proxy-down");
    expect(res).toMatchObject({ success: false, error: { code: "HTTP_502", message: "Bad Gateway" }, meta: { requestId: "r-9" } });
  });

  it("an empty 401 body is reported as an auth error", async () => {
    atPath("/login");
    server.use(http.get("/api/v1/me", () => new HttpResponse(null, { status: 401 })), http.post("/api/v1/auth/refresh", () => new HttpResponse(null, { status: 401 })));
    const res = await api.get("/api/v1/me");
    expect(res.error?.code).toBe("AUTH_002");
    expect(res.error?.message).toBeTruthy();
  });

  it("on 401 refreshes once and retries the request", async () => {
    let calls = 0;
    server.use(
      http.get("/api/v1/me", () => (++calls === 1 ? fail(401, "AUTH_001", "Token expired") : ok({ name: "Pragun" }))),
      http.post("/api/v1/auth/refresh", () => ok({})),
    );
    const res = await api.get("/api/v1/me");
    expect(res.data).toEqual({ name: "Pragun" });
    expect(calls).toBe(2);
  });

  it("concurrent 401s share one refresh", async () => {
    let refreshes = 0;
    const seen: Record<string, number> = {};
    server.use(
      http.get("/api/v1/:thing", ({ params }) => {
        const n = (seen[params.thing as string] = (seen[params.thing as string] ?? 0) + 1);
        return n === 1 ? fail(401, "AUTH_001", "expired") : ok(params.thing);
      }),
      http.post("/api/v1/auth/refresh", async () => { refreshes++; await new Promise((r) => setTimeout(r, 20)); return ok({}); }),
    );
    const [a, b] = await Promise.all([api.get("/api/v1/a"), api.get("/api/v1/b")]);
    expect([a.data, b.data]).toEqual(["a", "b"]);
    expect(refreshes).toBe(1);
  });

  it("when the refresh fails the session is over: go to /login", async () => {
    atPath("/transactions");
    server.use(http.get("/api/v1/me", () => fail(401, "AUTH_001", "expired")), http.post("/api/v1/auth/refresh", () => fail(401, "AUTH_004", "revoked")));
    const res = await api.get("/api/v1/me");
    expect(res.success).toBe(false);
    expect(assign).toHaveBeenCalledWith("/login");
  });

  it("a refresh that errors on the network also ends the session", async () => {
    atPath("/");
    server.use(http.get("/api/v1/me", () => fail(401, "AUTH_001", "expired")), http.post("/api/v1/auth/refresh", () => HttpResponse.error()));
    await api.get("/api/v1/me");
    expect(assign).toHaveBeenCalledWith("/login");
  });

  it("still 401 after a good refresh: redirect, no loop", async () => {
    atPath("/");
    server.use(http.get("/api/v1/me", () => fail(401, "AUTH_002", "invalid")), http.post("/api/v1/auth/refresh", () => ok({})));
    await api.get("/api/v1/me");
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it("does not redirect when already on /login, and never refreshes for auth endpoints", async () => {
    atPath("/login");
    let refreshed = false;
    server.use(http.post("/api/v1/auth/google", () => fail(401, "AUTH_002", "bad code")), http.post("/api/v1/auth/refresh", () => { refreshed = true; return ok({}); }));
    const res = await api.post("/api/v1/auth/google", { code: "x" });
    expect(res.error?.code).toBe("AUTH_002");
    expect(refreshed).toBe(false);
    expect(assign).not.toHaveBeenCalled();
  });

  it("logout revokes this device and goes to /login even if the call fails", async () => {
    atPath("/settings");
    server.use(http.post("/api/v1/auth/logout", () => HttpResponse.error()));
    await api.logout();
    expect(assign).toHaveBeenCalledWith("/login");
  });
});
