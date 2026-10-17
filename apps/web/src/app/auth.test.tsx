import { http } from "msw";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";

const realLocation = window.location;
afterEach(() => {
  vi.unstubAllEnvs();
  Object.defineProperty(window, "location", { configurable: true, value: realLocation });
});

/** A stand-in for window.location on /login whose assign() we can watch. */
function stubLocation() {
  const loc = { origin: "http://localhost:3000", href: "http://localhost:3000/login", pathname: "/login", assign: vi.fn() };
  Object.defineProperty(window, "location", { configurable: true, value: loc });
  return loc;
}

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

  it("with a client id it asks the API to start a sign-in and goes to the Google URL it returns (AOS-618)", async () => {
    const Login = await loginPage("client-123");
    const google = "https://accounts.google.com/o/oauth2/v2/auth?client_id=client-123&state=s1&code_challenge=c1";
    server.use(http.get("/api/v1/auth/google/start", () => ok({ url: google })));
    const loc = stubLocation();
    render(<Login />);
    await userEvent.click(screen.getByRole("button", { name: "Continue with Google" }));
    await waitFor(() => expect(loc.assign).toHaveBeenCalledWith(google));
  });

  it("a URL that isn't Google's sign-in page is never followed", async () => {
    const Login = await loginPage("client-123");
    server.use(http.get("/api/v1/auth/google/start", () => ok({ url: "https://evil.example/phish" })));
    const loc = stubLocation();
    render(<Login />);
    await userEvent.click(screen.getByRole("button", { name: "Continue with Google" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Sign-in couldn't start. Please try again.");
    expect(loc.assign).not.toHaveBeenCalled();
  });

  it("too many refused sign-ins are explained, and the button works again", async () => {
    const Login = await loginPage("client-123");
    server.use(http.get("/api/v1/auth/google/start", () => fail(429, "RATE_002", "Too many refused sign-ins. Try again in 2 min.")));
    stubLocation();
    render(<Login />);
    await userEvent.click(screen.getByRole("button", { name: "Continue with Google" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many refused sign-ins. Try again in 2 min.");
    expect(screen.getByRole("button", { name: "Continue with Google" })).toBeEnabled();
  });

  it("a network failure while starting is explained", async () => {
    const Login = await loginPage("client-123");
    const { api } = await import("@/lib/api");
    vi.spyOn(api, "get").mockRejectedValue(new Error("offline"));
    render(<Login />);
    await userEvent.click(screen.getByRole("button", { name: "Continue with Google" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Check your connection");
  });

  it.each([["auth_failed", "Authentication failed. Please try again."], ["private", "This AdminOS is private. Only its owner can sign in."], ["other", "Something went wrong. Please try again."]])(
    "error=%s is explained", async (error, text) => {
      const { Login } = await fresh("client-123", { error });
      render(<Login />);
      expect(screen.getByText(text)).toBeInTheDocument();
    });
});

describe("OAuth callback", () => {
  it.each([[{ error: "access_denied" }], [{}], [{ code: "abc" }], [{ state: "s1" }]])("no code or no state (%o) → back to login", async (q) => {
    const { nav, Callback: Page } = await fresh("client-123", q as Record<string, string>);
    render(<Page />);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?error=auth_failed"));
  });

  it.each([["completed", "/"], ["first_sync_done", "/"], ["started", "/onboarding"]])("onboarding %s → %s", async (status, path) => {
    const { nav, Callback: Page } = await fresh("client-123", { code: "abc", state: "s1" });
    let body: Record<string, string> = {};
    server.use(http.post("/api/v1/auth/google", async ({ request }) => { body = (await request.json()) as never; return ok({ expiresIn: 3600, user: { id: "u", email: "e", name: "n", role: "owner", onboardingStatus: status } }); }));
    render(<Page />);
    expect(screen.getByText("Signing you in...")).toBeInTheDocument();
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith(path));
    expect(body).toEqual({ code: "abc", state: "s1", redirectUri: "http://localhost:3000/auth/callback" });
  });

  it("a rejected code shows the failure and returns to login", async () => {
    const { nav, Callback: Page } = await fresh("client-123", { code: "bad", state: "s1" });
    server.use(http.post("/api/v1/auth/google", () => fail(401, "AUTH_002", "bad code")));
    Object.defineProperty(window, "location", { configurable: true, value: { origin: "http://localhost:3000", pathname: "/auth/callback", assign: vi.fn() } });
    render(<Page />);
    expect(await screen.findByText("Authentication failed. Redirecting...")).toBeInTheDocument();
    expect(nav.router.replace).toHaveBeenCalledWith("/login?error=auth_failed");
  });

  it("someone other than the owner is told the AdminOS is private (AOS-614)", async () => {
    const { nav, Callback: Page } = await fresh("client-123", { code: "abc", state: "s1" });
    const { api } = await import("@/lib/api");
    vi.spyOn(api, "post").mockResolvedValue({ success: false, data: null, error: { code: "USER_003", message: "This AdminOS is private" } } as never);
    render(<Page />);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/login?error=private"));
  });

  it("a network failure also returns to login", async () => {
    const { Callback: Page } = await fresh("client-123", { code: "abc", state: "s1" });
    const { api } = await import("@/lib/api");
    vi.spyOn(api, "post").mockRejectedValue(new Error("offline"));
    render(<Page />);
    expect(await screen.findByText("Authentication failed. Redirecting...")).toBeInTheDocument();
  });
});
