import { http } from "msw";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";

afterEach(() => vi.unstubAllEnvs());

import { nav } from "../../test/next";
import LoginPage from "./login/page";
import CallbackPage from "./auth/callback/page";

async function fresh(clientId = "client-123", search: Record<string, string> = {}) {
  vi.stubEnv("NEXT_PUBLIC_GOOGLE_CLIENT_ID", clientId);
  nav.search = new URLSearchParams(search);
  return { nav, Login: LoginPage, Callback: CallbackPage };
}
const loginPage = async (clientId: string) => (await fresh(clientId)).Login;

describe("Login", () => {
  it("without a client id the button is disabled and explains why", async () => {
    const Login = await loginPage("");
    render(<Login />);
    expect(screen.getByRole("alert")).toHaveTextContent("Google sign-in isn't configured");
    expect(screen.getByRole("button", { name: "Continue with Google" })).toBeDisabled();
  });

  it("with a client id it sends the user to Google with the right parameters", async () => {
    const Login = await loginPage("client-123");
    const loc = { origin: "http://localhost:3000", href: "" };
    Object.defineProperty(window, "location", { configurable: true, value: loc });
    render(<Login />);
    await userEvent.click(screen.getByRole("button", { name: "Continue with Google" }));
    const url = new URL(loc.href);
    expect(url.origin + url.pathname).toBe("https://accounts.google.com/o/oauth2/v2/auth");
    expect(Object.fromEntries(url.searchParams)).toMatchObject({
      client_id: "client-123", redirect_uri: "http://localhost:3000/auth/callback", response_type: "code", scope: "openid email profile",
    });
  });

  it.each([["auth_failed", "Authentication failed. Please try again."], ["private", "This AdminOS is private. Only its owner can sign in."], ["other", "Something went wrong. Please try again."]])(
    "error=%s is explained", async (error, text) => {
      const { Login } = await fresh("client-123", { error });
      render(<Login />);
      expect(screen.getByText(text)).toBeInTheDocument();
    });
});

describe("OAuth callback", () => {
  it.each([[{ error: "access_denied" }], [{}]])("no code (%o) → back to login", async (q) => {
    const { nav, Callback: Page } = await fresh("client-123", q as Record<string, string>);
    render(<Page />);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?error=auth_failed"));
  });

  it.each([["completed", "/"], ["first_sync_done", "/"], ["started", "/onboarding"]])("onboarding %s → %s", async (status, path) => {
    const { nav, Callback: Page } = await fresh("client-123", { code: "abc" });
    let body: Record<string, string> = {};
    server.use(http.post("/api/v1/auth/google", async ({ request }) => { body = (await request.json()) as never; return ok({ expiresIn: 3600, user: { id: "u", email: "e", name: "n", role: "owner", onboardingStatus: status } }); }));
    render(<Page />);
    expect(screen.getByText("Signing you in...")).toBeInTheDocument();
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith(path));
    expect(body).toEqual({ code: "abc", redirectUri: "http://localhost:3000/auth/callback" });
  });

  it("a rejected code shows the failure and returns to login", async () => {
    const { nav, Callback: Page } = await fresh("client-123", { code: "bad" });
    server.use(http.post("/api/v1/auth/google", () => fail(401, "AUTH_002", "bad code")));
    Object.defineProperty(window, "location", { configurable: true, value: { origin: "http://localhost:3000", pathname: "/auth/callback", assign: vi.fn() } });
    render(<Page />);
    expect(await screen.findByText("Authentication failed. Redirecting...")).toBeInTheDocument();
    expect(nav.router.replace).toHaveBeenCalledWith("/login?error=auth_failed");
  });

  it("someone other than the owner is told the AdminOS is private (AOS-614)", async () => {
    const { nav, Callback: Page } = await fresh("client-123", { code: "abc" });
    const { api } = await import("@/lib/api");
    vi.spyOn(api, "post").mockResolvedValue({ success: false, data: null, error: { code: "USER_003", message: "This AdminOS is private" } } as never);
    render(<Page />);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?error=private"));
  });

  it("a network failure also returns to login", async () => {
    const { Callback: Page } = await fresh("client-123", { code: "abc" });
    const { api } = await import("@/lib/api");
    vi.spyOn(api, "post").mockRejectedValue(new Error("offline"));
    render(<Page />);
    expect(await screen.findByText("Authentication failed. Redirecting...")).toBeInTheDocument();
  });
});
