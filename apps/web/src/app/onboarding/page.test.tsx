import { http } from "msw";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { nav } from "../../../test/next";
import OnboardingPage from "./page";

afterEach(() => vi.useRealTimers());

const sync = (over = {}) => ({ id: "sy-1", status: "running", totalItems: 10, processedItems: 4, duplicateItems: 1, netNewItems: 3, failedItems: 0, startedAt: null, ...over });

function connectGmail(syncOk = true) {
  server.use(
    http.post("/api/v1/connections", () => ok({ id: "c-1" })),
    http.post("/api/v1/connections/c-1/sync", () => (syncOk ? ok({ syncSessionId: "sy-1", status: "queued", totalItems: 0 }) : fail(409, "SYNC_001", "busy"))),
  );
}

describe("Onboarding", () => {
  const start = async () => {
    const user = vi.isFakeTimers() ? userEvent.setup({ advanceTimers: vi.advanceTimersByTime.bind(vi) }) : userEvent.setup();
    render(<OnboardingPage />);
    expect(screen.getByRole("heading", { name: "Welcome to AdminOS" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Get started" }));
    expect(screen.getByRole("heading", { name: "Connect Gmail" })).toBeInTheDocument();
    return user;
  };

  it("skip Gmail and SMS → straight to the dashboard", async () => {
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Skip for now" }));
    expect(screen.getByRole("button", { name: "Continue" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Skip for now" }));
    expect(nav.router.replace).toHaveBeenCalledWith("/");
  });

  it("skip Gmail, then Continue past SMS → results", async () => {
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Skip for now" }));
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(screen.getByRole("heading", { name: /Here's what we found/ })).toBeInTheDocument();
    expect(screen.getByText(/still processing/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Go to Dashboard" }));
    expect(nav.router.replace).toHaveBeenCalledWith("/");
  });

  it("connect Gmail → SMS → processing polls until done → results with subscriptions", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    connectGmail();
    let polls = 0;
    server.use(
      http.get("/api/v1/sync/sy-1", () => (++polls < 3 ? ok(sync(polls === 1 ? { netNewItems: 0, duplicateItems: 0 } : {})) : ok(sync({ status: "completed" })))),
      http.get("/api/v1/subscriptions", () => ok([fx.subscription(), fx.subscription({ id: "s-2", name: "Gym", amount: 1500, isFlagged: true })])),
    );
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Connect Gmail" }));
    await user.click(await screen.findByRole("button", { name: "I've set it up" }));
    expect(screen.getByText("This may take a moment...")).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(2100); });
    expect(await screen.findByText("Processed 4 of 10 items")).toBeInTheDocument();
    expect(screen.queryByText(/new transactions so far/)).not.toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(2100); });
    expect(await screen.findByText("Found 3 new transactions so far...")).toBeInTheDocument();
    expect(screen.getByText("Skipped 1 duplicates")).toBeInTheDocument();
    await act(async () => { vi.advanceTimersByTime(2100); });
    expect(await screen.findByText("₹2,149")).toBeInTheDocument();
    expect(screen.getByText(/2 subscriptions detected · 1 potentially unused/)).toBeInTheDocument();
    expect(screen.getByText("₹1,500/monthly")).toBeInTheDocument();
  });

  it("one subscription is singular; a failed sync still finishes; poll errors keep polling", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    connectGmail();
    let polls = 0;
    server.use(
      http.get("/api/v1/sync/sy-1", () => (++polls === 1 ? fail(500, "SERVER_001", "x") : polls === 2 ? fail(404, "NOT_FOUND", "x") : ok(sync({ status: "failed" })))),
      http.get("/api/v1/subscriptions", () => ok([fx.subscription()])),
    );
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Connect Gmail" }));
    await user.click(await screen.findByRole("button", { name: "Skip for now" }));
    for (let i = 0; i < 3; i++) await act(async () => { vi.advanceTimersByTime(2100); });
    expect(await screen.findByText("1 subscription detected")).toBeInTheDocument();
  });

  it("a network error while polling keeps polling", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    connectGmail();
    let polls = 0;
    const { api } = await import("@/lib/api");
    const get = api.get.bind(api);
    vi.spyOn(api, "get").mockImplementation((path: string) => (path.startsWith("/api/v1/sync") && ++polls === 1 ? Promise.reject(new Error("offline")) : get(path)) as never);
    server.use(http.get("/api/v1/sync/sy-1", () => ok(sync({ status: "completed" }))), http.get("/api/v1/subscriptions", () => fail(500, "SERVER_001", "x")));
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Connect Gmail" }));
    await user.click(await screen.findByRole("button", { name: "I've set it up" }));
    for (let i = 0; i < 2; i++) await act(async () => { vi.advanceTimersByTime(2100); });
    expect(await screen.findByText(/still processing/)).toBeInTheDocument();
  });

  it("Gmail connected but the sync didn't start: SMS set up or skipped → results, never an endless spinner", async () => {
    connectGmail(false);
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Connect Gmail" }));
    await user.click(await screen.findByRole("button", { name: "Skip for now" }));
    expect(screen.getByRole("heading", { name: /Here's what we found/ })).toBeInTheDocument();
  });

  it("the same with I've set it up", async () => {
    connectGmail(false);
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Connect Gmail" }));
    await user.click(await screen.findByRole("button", { name: "I've set it up" }));
    expect(screen.getByRole("heading", { name: /Here's what we found/ })).toBeInTheDocument();
  });

  it("Gmail connection refused or offline still moves on to SMS", async () => {
    server.use(http.post("/api/v1/connections", () => fail(500, "CONN_002", "OAuth failed")));
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Connect Gmail" }));
    expect(await screen.findByRole("heading", { name: "Enable SMS Parsing" })).toBeInTheDocument();
  });

  it("a network error connecting Gmail also moves on", async () => {
    const { api } = await import("@/lib/api");
    vi.spyOn(api, "post").mockRejectedValue(new Error("offline"));
    const user = await start();
    await user.click(screen.getByRole("button", { name: "Connect Gmail" }));
    expect(await screen.findByRole("heading", { name: "Enable SMS Parsing" })).toBeInTheDocument();
  });

  it("the progress bar fills step by step", async () => {
    render(<OnboardingPage />);
    const bars = () => document.querySelectorAll(".h-1.flex-1");
    expect([...bars()].filter((b) => b.classList.contains("bg-blue-600"))).toHaveLength(1);
    await userEvent.click(screen.getByRole("button", { name: "Get started" }));
    expect([...bars()].filter((b) => b.classList.contains("bg-blue-600"))).toHaveLength(2);
  });
});
