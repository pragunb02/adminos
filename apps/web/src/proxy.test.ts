import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { config, proxy } from "./proxy";

const req = (path: string, cookie?: string) =>
  new NextRequest(new URL(path, "http://localhost:3000"), { headers: cookie ? { cookie } : {} });

describe("route guard (proxy.ts)", () => {
  it.each(["/api/v1/auth/google", "/login", "/auth/callback?code=x", "/health"])("%s is public", (path) => {
    expect(proxy(req(path)).headers.get("location")).toBeNull();
  });

  it("app pages without a session go to /login", () => {
    const res = proxy(req("/transactions"));
    expect(res.status).toBe(307);
    expect(res.headers.get("location")).toBe("http://localhost:3000/login");
  });

  it("app pages with a session pass", () => {
    expect(proxy(req("/transactions", "access_token=jwt")).headers.get("location")).toBeNull();
  });

  it("static assets are not matched", () => {
    expect(config.matcher[0]).toContain("_next/static");
  });
});
