import { StrictMode } from "react";
import { http, HttpResponse } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../../../../test/msw/server";
import { fail, ok } from "../../../../../test/msw/envelope";
import { fx } from "../../../../../test/fixtures";
import { nav } from "../../../../../test/next";
import { renderWithQuery } from "../../../../../test/render";
import { browser } from "@/lib/gmail";
import GmailCallbackPage from "./page";

/** AOS-406: Google's answer comes back here; the code and state are posted once and every outcome is said in words. */
describe("Connect Gmail callback", () => {
  const at = (query: string) => {
    nav.pathname = "/connections/gmail/callback";
    nav.search = new URLSearchParams(query);
  };
  const posts: unknown[] = [];
  const connectReplies = (reply: () => Response) =>
    server.use(http.post("/api/v1/connections", async ({ request }) => { posts.push(await request.json()); return reply(); }));

  it("posts Google's code and state once, shows progress, then shows success at a clean address", async () => {
    posts.length = 0;
    at("code=4%2F0AbCd&state=s-123&scope=email");
    let release: () => void = () => {};
    const gate = new Promise<void>((r) => { release = r; });
    server.use(http.post("/api/v1/connections", async ({ request }) => {
      posts.push(await request.json());
      await gate;
      return ok(fx.connection({ gmailAddress: "owner@example.com" }));
    }));
    renderWithQuery(<StrictMode><GmailCallbackPage /></StrictMode>);
    expect(await screen.findByRole("status")).toHaveTextContent("Connecting Gmail…");
    release();
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/connections/gmail/callback?connected=1"));
    expect(posts).toEqual([{ sourceType: "gmail", code: "4/0AbCd", state: "s-123", redirectUri: "http://localhost:3000/connections/gmail/callback" }]);
  });

  it("after connecting it says the first sync started and links back", async () => {
    at("connected=1");
    renderWithQuery(<GmailCallbackPage />);
    const done = await screen.findByRole("status");
    expect(done).toHaveTextContent("Gmail connected — first sync started");
    expect(within(done).getByRole("link", { name: "Back to Settings" })).toHaveAttribute("href", "/settings");
    expect(within(done).getByRole("link", { name: "Go to Home" })).toHaveAttribute("href", "/");
  });

  it("a cancelled consent says so and posts nothing", async () => {
    posts.length = 0;
    at("error=access_denied&state=s-123");
    connectReplies(() => ok({}));
    renderWithQuery(<GmailCallbackPage />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("You didn't allow access");
    expect(within(alert).getByRole("button", { name: "Start again" })).toBeInTheDocument();
    expect(within(alert).getByRole("link", { name: "Back to Settings" })).toBeInTheDocument();
    expect(posts).toEqual([]);
  });

  it("another Google error is quoted", async () => {
    at("error=server_error");
    renderWithQuery(<GmailCallbackPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent(`Google answered "server_error"`);
  });

  it.each(["code=abc", "state=s-1", ""])("an address without both code and state (%s) explains how to get here", async (query) => {
    at(query);
    renderWithQuery(<GmailCallbackPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("This page needs Google's answer");
  });

  it.each([
    ["CONN_010", 400, "This sign-in didn't start here", true],
    ["CONN_011", 400, "The sign-in took too long", true],
    ["CONN_012", 400, "This sign-in was already used", true],
    ["CONN_007", 400, "Gmail access wasn't allowed", true],
    ["CONN_008", 400, "Google didn't give lasting access", true],
    ["CONN_002", 400, "Google didn't accept the sign-in", true],
    ["CONN_006", 400, "Google sign-in isn't set up", false],
    ["CONN_009", 503, "Google sign-in isn't set up", false],
  ] as const)("the API's %s is shown in words", async (code, status, title, canRetry) => {
    at("code=c&state=s");
    connectReplies(() => fail(status, code, "api message"));
    renderWithQuery(<GmailCallbackPage />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(title);
    expect(within(alert).queryByRole("button", { name: "Start again" }) !== null).toBe(canRetry);
  });

  it("an unexpected API error shows the API's message", async () => {
    at("code=c&state=s");
    connectReplies(() => fail(500, "SERVER_001", "Database unavailable"));
    renderWithQuery(<GmailCallbackPage />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Gmail isn't connected");
    expect(alert).toHaveTextContent("Database unavailable");
  });

  it("an unreachable API asks to start again", async () => {
    at("code=c&state=s");
    connectReplies(() => HttpResponse.error());
    renderWithQuery(<GmailCallbackPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("AdminOS couldn't be reached");
  });

  it("Start again asks for a fresh consent and goes to Google", async () => {
    const go = vi.spyOn(browser, "go").mockImplementation(() => {});
    at("code=c&state=s");
    connectReplies(() => fail(400, "CONN_011", "expired"));
    server.use(http.get("/api/v1/connections/gmail/authorize-url", () => ok({ url: "https://accounts.google.com/o/oauth2/v2/auth?state=fresh" })));
    const { user } = renderWithQuery(<GmailCallbackPage />);
    await user.click(await screen.findByRole("button", { name: "Start again" }));
    await waitFor(() => expect(go).toHaveBeenCalledWith("https://accounts.google.com/o/oauth2/v2/auth?state=fresh"));
  });
});
