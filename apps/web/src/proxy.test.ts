import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { config, proxy } from "./proxy";

const req = (path: string, cookie?: string) =>
  new NextRequest(new URL(path, "http://localhost:3000"), { headers: cookie ? { cookie } : {} });

describe("route guard (proxy.ts)", () => {
  it.each(["/api/v1/auth/google", "/login", "/auth/callback?code=x", "/health"])("%s is public", (path) => {
    expect(proxy(req(path)).headers.get("location")).toBeNull();
  });

  it.each(["/manifest.webmanifest", "/sw.js", "/icon-192.png", "/icon-512.png", "/apple-icon.png"])(
    "%s is public: browsers fetch it without the session cookie (AOS-510)",
    (path) => expect(proxy(req(path)).headers.get("location")).toBeNull(),
  );

  it("only those exact files: a look-alike path is still guarded", () => {
    expect(proxy(req("/sw.js.map")).headers.get("location")).toBe("http://localhost:3000/login");
  });

  // AUD-13 (AOS-619): public pages are matched exactly, not by prefix.
  it.each(["/login-admin", "/loginx", "/healthz", "/health/../transactions", "/auth/callbackx", "/auth/callback/extra", "/login/extra"])(
    "%s is not public just because it starts like a public page",
    (path) => expect(proxy(req(path)).headers.get("location")).toBe("http://localhost:3000/login"),
  );

  it("a trailing slash on a public page is still public", () => {
    expect(proxy(req("/login/")).headers.get("location")).toBeNull();
  });

  it("an empty session cookie counts as no session", () => {
    expect(proxy(req("/transactions", "access_token=")).headers.get("location")).toBe("http://localhost:3000/login");
  });

  it("an /api look-alike outside /api/ is guarded like a page", () => {
    expect(proxy(req("/api")).headers.get("location")).toBe("http://localhost:3000/login");
    expect(proxy(req("/apiv1/x")).headers.get("location")).toBe("http://localhost:3000/login");
  });

  it("app pages without a session go to /login", () => {
    const res = proxy(req("/transactions"));
    expect(res.status).toBe(307);
    expect(res.headers.get("location")).toBe("http://localhost:3000/login");
  });

  it("the Connect Gmail callback is for signed-in users only (AOS-406)", () => {
    // Google's code is redeemed for the signed-in user; without a session there is no one to bind it to.
    expect(proxy(req("/connections/gmail/callback?code=x&state=y")).headers.get("location")).toBe("http://localhost:3000/login");
    expect(proxy(req("/connections/gmail/callback?code=x&state=y", "access_token=jwt")).headers.get("location")).toBeNull();
  });

  it("app pages with a session pass", () => {
    expect(proxy(req("/transactions", "access_token=jwt")).headers.get("location")).toBeNull();
  });

  it("static assets are not matched", () => {
    expect(config.matcher[0]).toContain("_next/static");
  });
});
