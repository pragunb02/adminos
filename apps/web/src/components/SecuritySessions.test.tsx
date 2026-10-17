import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { renderWithQuery } from "../../test/render";
import { fx } from "../../test/fixtures";
import SecuritySessions, { signedOut } from "./SecuritySessions";
import SecurityPage from "@/app/settings/security/page";

const here = fx.session({ id: "s-here", device: "Safari on Mac", current: true, ipAddress: null });
const phone = fx.session({ id: "s-phone", device: "Safari on iPhone", current: false, lastActiveAt: null });

afterEach(() => vi.restoreAllMocks());

/** Settings → Security (AOS-620): sessions with device and last activity; sign out one, others, or everywhere. */
describe("SecuritySessions", () => {
  it("lists each session by device, marks this one, and shows when it was last active", async () => {
    server.use(http.get("/api/v1/auth/sessions", () => ok([here, phone])));
    renderWithQuery(<SecuritySessions />);
    const mine = await screen.findByRole("listitem", { name: "Safari on Mac" });
    expect(within(mine).getByText("This device")).toBeInTheDocument();
    expect(within(mine).getByText(/Last active/)).toBeInTheDocument();
    const other = screen.getByRole("listitem", { name: "Safari on iPhone" });
    expect(within(other).queryByText("This device")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out everywhere else" })).toBeInTheDocument();
  });

  it("renders device names and addresses as text, never markup", async () => {
    server.use(http.get("/api/v1/auth/sessions", () => ok([fx.session({ id: "x", device: "<img src=x onerror=alert(1)>", ipAddress: "<b>1.2.3.4</b>" })])));
    const { container } = renderWithQuery(<SecuritySessions />);
    expect(await screen.findByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("b")).toBeNull();
  });

  it("an empty list and a load failure are both explained", async () => {
    server.use(http.get("/api/v1/auth/sessions", () => ok([])));
    renderWithQuery(<SecuritySessions />);
    expect(await screen.findByText("No active sessions.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sign out everywhere else" })).not.toBeInTheDocument();
  });

  it("a failed load shows the error", async () => {
    server.use(http.get("/api/v1/auth/sessions", () => fail(500, "SERVER_001", "Internal server error")));
    renderWithQuery(<SecuritySessions />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Internal server error");
  });

  it("signing out another device revokes it and says so", async () => {
    let list = [here, phone];
    const deleted: string[] = [];
    server.use(
      http.get("/api/v1/auth/sessions", () => ok(list)),
      http.delete("/api/v1/auth/sessions/:id", ({ params }) => { deleted.push(String(params.id)); list = [here]; return ok({ message: "Session revoked" }); }),
    );
    const go = vi.spyOn(signedOut, "go").mockImplementation(() => undefined);
    const { user } = renderWithQuery(<SecuritySessions />);
    const other = await screen.findByRole("listitem", { name: "Safari on iPhone" });
    await user.click(within(other).getByRole("button", { name: "Sign out" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Safari on iPhone is signed out.");
    expect(deleted).toEqual(["s-phone"]);
    await waitFor(() => expect(screen.queryByRole("listitem", { name: "Safari on iPhone" })).not.toBeInTheDocument());
    expect(go).not.toHaveBeenCalled();
  });

  it("signing out this device goes to the login page", async () => {
    server.use(http.get("/api/v1/auth/sessions", () => ok([here])), http.delete("/api/v1/auth/sessions/:id", () => ok({})));
    const go = vi.spyOn(signedOut, "go").mockImplementation(() => undefined);
    const { user } = renderWithQuery(<SecuritySessions />);
    const mine = await screen.findByRole("listitem", { name: "Safari on Mac" });
    await user.click(within(mine).getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(go).toHaveBeenCalled());
  });

  it("sign out everywhere else keeps this device, after a confirmation", async () => {
    const calls: string[] = [];
    server.use(
      http.get("/api/v1/auth/sessions", () => ok([here, phone])),
      http.post("/api/v1/auth/sessions/revoke-all", ({ request }) => { calls.push(new URL(request.url).search); return ok({ revoked: 1, signedOut: false }); }),
    );
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const go = vi.spyOn(signedOut, "go").mockImplementation(() => undefined);
    const { user } = renderWithQuery(<SecuritySessions />);
    await user.click(await screen.findByRole("button", { name: "Sign out everywhere else" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Every other device is signed out.");
    expect(calls).toEqual(["?keepCurrent=true"]);
    expect(go).not.toHaveBeenCalled();
  });

  it("sign out everywhere ends this device too", async () => {
    server.use(http.get("/api/v1/auth/sessions", () => ok([here, phone])), http.post("/api/v1/auth/sessions/revoke-all", () => ok({ revoked: 2, signedOut: true })));
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const go = vi.spyOn(signedOut, "go").mockImplementation(() => undefined);
    const { user } = renderWithQuery(<SecuritySessions />);
    await user.click(await screen.findByRole("button", { name: "Sign out everywhere" }));
    await waitFor(() => expect(go).toHaveBeenCalled());
  });

  it("cancelling the confirmation does nothing", async () => {
    let called = false;
    server.use(http.get("/api/v1/auth/sessions", () => ok([here])), http.post("/api/v1/auth/sessions/revoke-all", () => { called = true; return ok({ revoked: 1, signedOut: true }); }));
    vi.spyOn(window, "confirm").mockReturnValue(false);
    const { user } = renderWithQuery(<SecuritySessions />);
    await user.click(await screen.findByRole("button", { name: "Sign out everywhere" }));
    expect(called).toBe(false);
  });

  it("a failed sign-out is shown and the buttons work again", async () => {
    server.use(http.get("/api/v1/auth/sessions", () => ok([here, phone])), http.delete("/api/v1/auth/sessions/:id", () => fail(404, "AUTH_006", "Session not found")));
    const { user } = renderWithQuery(<SecuritySessions />);
    const other = await screen.findByRole("listitem", { name: "Safari on iPhone" });
    await user.click(within(other).getByRole("button", { name: "Sign out" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Session not found");
    expect(within(other).getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("the page has a heading and a way back to Settings", async () => {
    server.use(http.get("/api/v1/auth/sessions", () => ok([])));
    renderWithQuery(<SecurityPage />);
    expect(screen.getByRole("heading", { level: 2, name: "Security" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "← Settings" })).toHaveAttribute("href", "/settings");
  });
});
