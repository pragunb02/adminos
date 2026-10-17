import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { browser } from "@/lib/gmail";
import { server } from "../../../test/msw/server";
import { fail, ok } from "../../../test/msw/envelope";
import { fx } from "../../../test/fixtures";
import { renderWithQuery } from "../../../test/render";
import SettingsPage from "./page";

describe("Settings", () => {
  const routes = (o: { connections?: unknown[]; prefs?: unknown; sessions?: unknown[]; failAll?: boolean } = {}) =>
    server.use(
      http.get("/api/v1/connections", () => (o.failAll ? fail(500, "SERVER_001", "x") : ok(o.connections ?? [fx.connection(), fx.connection({ id: "c-2", sourceType: "sms", status: "error", lastSyncedAt: null })]))),
      http.get("/api/v1/users/me/notification-preferences", () => (o.failAll ? fail(500, "SERVER_001", "x") : ok(o.prefs ?? fx.prefs()))),
      http.get("/api/v1/auth/sessions", () => ok(o.sessions ?? [fx.session(), fx.session({ id: "se-2", userAgent: null, ipAddress: null, lastActiveAt: null })])),
      http.get("/api/v1/device-tokens", () => ok([])),
    );

  it("shows sources, preferences, devices and sessions", async () => {
    routes();
    renderWithQuery(<SettingsPage />);
    expect(screen.getByText("Loading settings...")).toBeInTheDocument();
    expect(await screen.findByText("gmail")).toBeInTheDocument();
    expect(screen.getAllByText("120 items synced")).toHaveLength(2);
    expect(screen.getAllByText(/Last sync:/)).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Reconnect" })).toHaveLength(1);
    expect(screen.getByRole("switch", { name: "Weekly briefing" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("switch", { name: "Push notifications" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("heading", { name: "Connected devices" })).toBeInTheDocument();
    expect(screen.getByText("Safari on Mac")).toBeInTheDocument();
    expect(screen.getByText("Unknown device")).toBeInTheDocument();
    expect(screen.getByText("Unknown IP")).toBeInTheDocument();
  });

  it("empty connections and sessions say so; no briefing day when weekly briefing is off", async () => {
    routes({ connections: [], sessions: [], prefs: fx.prefs({ weeklyBriefing: false }) });
    renderWithQuery(<SettingsPage />);
    expect(await screen.findByText("No data sources connected yet.")).toBeInTheDocument();
    expect(screen.getByText("No active sessions.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Briefing day")).not.toBeInTheDocument();
  });

  it("toggles change at once and are saved together after a pause", async () => {
    routes();
    const saved: unknown[] = [];
    server.use(http.patch("/api/v1/users/me/notification-preferences", async ({ request }) => { saved.push(await request.json()); return ok({}); }));
    const { user } = renderWithQuery(<SettingsPage />);
    const bills = await screen.findByRole("switch", { name: "Bill reminders" });
    await user.click(bills);
    expect(bills).toHaveAttribute("aria-checked", "false");
    await user.selectOptions(screen.getByLabelText("Briefing day"), "fri");
    await user.clear(screen.getByLabelText("Quiet hours start"));
    await user.type(screen.getByLabelText("Quiet hours start"), "23:00");
    await waitFor(() => expect(saved).toHaveLength(1), { timeout: 2000 });
    expect(saved[0]).toMatchObject({ billReminders: false, briefingDay: "fri", quietHoursStart: "23:00" });
    expect(await screen.findByText("Saving...").catch(() => null)).toBeNull();
  });

  it("every preference control saves its key", async () => {
    routes();
    const saved: Record<string, unknown> = {};
    server.use(http.patch("/api/v1/users/me/notification-preferences", async ({ request }) => { Object.assign(saved, await request.json()); return ok({}); }));
    const { user } = renderWithQuery(<SettingsPage />);
    for (const name of ["Weekly briefing", "Anomaly alerts", "Subscription flags", "Email notifications", "Push notifications"]) {
      await user.click(await screen.findByRole("switch", { name }));
    }
    await user.click(screen.getByRole("switch", { name: "Weekly briefing" })); // back on, so its fields show
    await user.clear(screen.getByLabelText("Briefing time"));
    await user.type(screen.getByLabelText("Briefing time"), "07:30");
    await user.clear(screen.getByLabelText("Quiet hours end"));
    await user.type(screen.getByLabelText("Quiet hours end"), "07:00");
    await waitFor(() => expect(saved).toMatchObject({ weeklyBriefing: true, anomalyAlerts: false, subscriptionFlags: false, emailNotifications: false, pushNotifications: true, briefingTime: "07:30", quietHoursEnd: "07:00" }), { timeout: 2000 });
  });

  it("a failed save shows an error and reloads the real values", async () => {
    routes();
    server.use(http.patch("/api/v1/users/me/notification-preferences", () => fail(500, "SERVER_001", "Save failed")));
    const { user } = renderWithQuery(<SettingsPage />);
    const alerts = await screen.findByRole("switch", { name: "Anomaly alerts" });
    await user.click(alerts);
    expect(await screen.findByText("Save failed", {}, { timeout: 2000 })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("switch", { name: "Anomaly alerts" })).toHaveAttribute("aria-checked", "true"));
  });

  it("sync, reconnect and disconnect call the API; disconnect refreshes", async () => {
    let connections = [fx.connection(), fx.connection({ id: "c-2", sourceType: "sms", status: "error" })];
    const calls: string[] = [];
    routes();
    server.use(
      http.get("/api/v1/connections", () => ok(connections)),
      http.post("/api/v1/connections/c-1/sync", () => { calls.push("sync"); return ok({}); }),
      http.post("/api/v1/connections", async ({ request }) => { calls.push(`reconnect:${((await request.json()) as { sourceType: string }).sourceType}`); return ok({ id: "c-2" }); }),
      http.delete("/api/v1/connections/c-1", () => { calls.push("disconnect"); connections = []; return ok({}); }),
    );
    const { user } = renderWithQuery(<SettingsPage />);
    const gmail = await screen.findByRole("listitem", { name: "gmail connection" });
    const sms = screen.getByRole("listitem", { name: "sms connection" });
    expect(within(sms).queryByRole("button", { name: "Sync" })).not.toBeInTheDocument();
    await user.click(within(gmail).getByRole("button", { name: "Sync" }));
    await user.click(within(sms).getByRole("button", { name: "Reconnect" }));
    await user.click(within(gmail).getByRole("button", { name: "Disconnect" }));
    expect(await screen.findByText("No data sources connected yet.")).toBeInTheDocument();
    expect(calls).toEqual(["sync", "reconnect:sms", "disconnect"]);
  });

  // --- Connect Gmail (AOS-406) ---

  const consentAt = (calls: string[]) =>
    http.get("/api/v1/connections/gmail/authorize-url", ({ request }) => {
      calls.push(new URL(request.url).searchParams.get("redirectUri") ?? "");
      return ok({ url: "https://accounts.google.com/o/oauth2/v2/auth?state=s-1" });
    });

  it("without gmail, Connect Gmail asks the API for the consent url and goes to Google", async () => {
    const go = vi.spyOn(browser, "go").mockImplementation(() => {});
    const asked: string[] = [];
    routes({ connections: [fx.connection({ id: "c-2", sourceType: "sms" })] });
    server.use(consentAt(asked));
    const { user } = renderWithQuery(<SettingsPage />);
    await user.click(await screen.findByRole("button", { name: "Connect Gmail" }));
    await waitFor(() => expect(go).toHaveBeenCalledWith("https://accounts.google.com/o/oauth2/v2/auth?state=s-1"));
    expect(asked).toEqual(["http://localhost:3000/connections/gmail/callback"]);
    expect(screen.getByRole("button", { name: "Opening Google…" })).toBeDisabled();
  });

  it("when Google sign-in isn't configured, Connect Gmail says so", async () => {
    routes({ connections: [] });
    server.use(http.get("/api/v1/connections/gmail/authorize-url", () => fail(503, "CONN_009", "Google sign-in is not configured")));
    const { user } = renderWithQuery(<SettingsPage />);
    await user.click(await screen.findByRole("button", { name: "Connect Gmail" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Google sign-in isn't set up");
    expect(screen.getByRole("button", { name: "Connect Gmail" })).toBeEnabled();
  });

  it("a gmail in error shows its last error and reconnects through Google", async () => {
    const go = vi.spyOn(browser, "go").mockImplementation(() => {});
    routes({ connections: [fx.connection({ status: "error", gmailAddress: "pragun.b@gmail.com", lastError: "gmail access revoked: reconnect Gmail" })] });
    server.use(consentAt([]));
    const { user } = renderWithQuery(<SettingsPage />);
    const row = await screen.findByRole("listitem", { name: "gmail connection" });
    expect(within(row).getByText("pragun.b@gmail.com")).toBeInTheDocument();
    expect(within(row).getByText("Last error: gmail access revoked: reconnect Gmail")).toBeInTheDocument();
    expect(within(row).queryByRole("button", { name: "Sync" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Connect Gmail" })).not.toBeInTheDocument();
    await user.click(within(row).getByRole("button", { name: "Reconnect" }));
    await waitFor(() => expect(go).toHaveBeenCalledTimes(1));
  });

  it("a disconnected gmail offers Reconnect and no Disconnect", async () => {
    routes({ connections: [fx.connection({ status: "disconnected" })] });
    renderWithQuery(<SettingsPage />);
    const row = await screen.findByRole("listitem", { name: "gmail connection" });
    expect(within(row).getByRole("button", { name: "Reconnect" })).toBeInTheDocument();
    expect(within(row).queryByRole("button", { name: "Disconnect" })).not.toBeInTheDocument();
  });

  it("a connected gmail says whether updates are instant or daily", async () => {
    routes({ connections: [fx.connection({ watchExpiresAt: "2999-10-24T06:00:00Z" }), fx.connection({ id: "c-3", watchExpiresAt: "2020-01-01T00:00:00Z" })] });
    renderWithQuery(<SettingsPage />);
    expect(await screen.findByText(/^Instant updates on until/)).toBeInTheDocument();
    expect(screen.getByText("Checked once a day")).toBeInTheDocument();
  });

  it("a failed action is shown", async () => {
    routes({ connections: [fx.connection()] });
    server.use(http.post("/api/v1/connections/c-1/sync", () => fail(409, "SYNC_001", "Sync already in progress")));
    const { user } = renderWithQuery(<SettingsPage />);
    await user.click(await screen.findByRole("button", { name: "Sync" }));
    expect(await screen.findByText("Sync already in progress")).toBeInTheDocument();
  });

  it("everything failing is an error with retry", async () => {
    routes({ failAll: true });
    const { user } = renderWithQuery(<SettingsPage />);
    expect(await screen.findByText("Failed to load settings")).toBeInTheDocument();
    routes();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("gmail")).toBeInTheDocument();
  });
});
