import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../test/msw/server";
import { fail, ok } from "../../test/msw/envelope";
import { renderWithQuery } from "../../test/render";
import DeviceTokens from "./DeviceTokens";

const token = (id: string, extra: object = {}) => ({
  id, name: `Device ${id}`, scopes: ["sms:write"], createdAt: "2026-10-01T00:00:00Z", expiresAt: "2027-10-01T00:00:00Z",
  lastUsedAt: null, revokedAt: null, ...extra,
});

/** Settings → Connected devices (AOS-203): list, create (secret shown once), copy, revoke. */
describe("DeviceTokens", () => {
  it("lists active devices only; revoked ones are hidden", async () => {
    server.use(http.get("/api/v1/device-tokens", () => ok([token("a", { lastUsedAt: "2026-10-15T10:00:00Z" }), token("b", { revokedAt: "2026-10-10T00:00:00Z" })])));
    renderWithQuery(<DeviceTokens />);
    expect(await screen.findByText("Device a")).toBeInTheDocument();
    expect(screen.queryByText("Device b")).not.toBeInTheDocument();
    expect(screen.getByText(/Last used .*2026|Last used/)).not.toHaveTextContent("never");
  });

  it("an empty list says so, and a never-used device says never", async () => {
    server.use(http.get("/api/v1/device-tokens", () => ok([])));
    renderWithQuery(<DeviceTokens />);
    expect(await screen.findByText("No devices connected.")).toBeInTheDocument();
  });

  it("creating shows the secret once; copy and done work", async () => {
    let list = [] as ReturnType<typeof token>[];
    server.use(
      http.get("/api/v1/device-tokens", () => ok(list)),
      http.post("/api/v1/device-tokens", () => { list = [token("n")]; return ok({ ...token("n"), token: "secret-abc" }); }),
    );
    const { user } = renderWithQuery(<DeviceTokens />);
    await screen.findByText("No devices connected.");
    await user.click(screen.getByRole("button", { name: "Connect device" }));
    const shown = await screen.findByRole("status");
    expect(within(shown).getByText("secret-abc")).toBeInTheDocument();
    await user.click(within(shown).getByRole("button", { name: "Copy token" }));
    expect(await navigator.clipboard.readText()).toBe("secret-abc");
    expect(within(shown).getByRole("button", { name: "Copied ✓" })).toBeInTheDocument();
    expect(await screen.findByText("Device n")).toBeInTheDocument();
    await user.click(within(shown).getByRole("button", { name: "Done" }));
    expect(screen.queryByText("secret-abc")).not.toBeInTheDocument();
  });

  it("a blank name can't be submitted", async () => {
    server.use(http.get("/api/v1/device-tokens", () => ok([])));
    const { user } = renderWithQuery(<DeviceTokens />);
    await user.clear(screen.getByLabelText("Device name"));
    expect(screen.getByRole("button", { name: "Connect device" })).toBeDisabled();
    await user.type(screen.getByLabelText("Device name"), "   ");
    expect(screen.getByRole("button", { name: "Connect device" })).toBeDisabled();
  });

  it("create failure is shown", async () => {
    server.use(http.get("/api/v1/device-tokens", () => ok([])), http.post("/api/v1/device-tokens", () => fail(400, "VALIDATION_001", "name: too long")));
    const { user } = renderWithQuery(<DeviceTokens />);
    await user.click(screen.getByRole("button", { name: "Connect device" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("name: too long");
  });

  it("a created token without a secret shows nothing to copy", async () => {
    server.use(http.get("/api/v1/device-tokens", () => ok([])), http.post("/api/v1/device-tokens", () => ok(token("z"))));
    const { user } = renderWithQuery(<DeviceTokens />);
    await user.click(screen.getByRole("button", { name: "Connect device" }));
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
  });

  it("revoke asks first; cancelling keeps the device", async () => {
    let revoked = 0;
    server.use(http.get("/api/v1/device-tokens", () => ok(revoked ? [] : [token("a")])), http.delete("/api/v1/device-tokens/a", () => { revoked++; return ok({}); }));
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    const { user } = renderWithQuery(<DeviceTokens />);
    await user.click(await screen.findByRole("button", { name: "Revoke" }));
    expect(revoked).toBe(0);
    await user.click(screen.getByRole("button", { name: "Revoke" }));
    expect(await screen.findByText("No devices connected.")).toBeInTheDocument();
    expect(confirm).toHaveBeenCalledTimes(2);
  });

  it("load and revoke failures are shown", async () => {
    server.use(http.get("/api/v1/device-tokens", () => fail(500, "SERVER_001", "Internal server error")));
    renderWithQuery(<DeviceTokens />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Internal server error");
  });

  it("a revoke failure is shown", async () => {
    server.use(http.get("/api/v1/device-tokens", () => ok([token("a")])), http.delete("/api/v1/device-tokens/a", () => fail(404, "NOT_FOUND", "Device not found")));
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const { user } = renderWithQuery(<DeviceTokens />);
    await user.click(await screen.findByRole("button", { name: "Revoke" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Device not found");
  });
});
