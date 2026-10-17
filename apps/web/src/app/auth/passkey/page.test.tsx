import { http } from "msw";
import { screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { server } from "../../../../test/msw/server";
import { fail, ok } from "../../../../test/msw/envelope";
import { renderWithQuery } from "../../../../test/render";
import { nav } from "../../../../test/next";
import { ceremony, passkeyName } from "@/lib/passkeys";
import PasskeyPage from "./page";
import PasskeyManager from "@/components/PasskeyManager";

const fakeCredential = (kind: "create" | "get") => ({
  type: "public-key", id: "Bg", rawId: new Uint8Array([6]).buffer,
  response: kind === "create"
    ? { clientDataJSON: new Uint8Array([1]).buffer, attestationObject: new Uint8Array([2]).buffer, getTransports: () => ["internal"] }
    : { clientDataJSON: new Uint8Array([1]).buffer, authenticatorData: new Uint8Array([3]).buffer, signature: new Uint8Array([4]).buffer, userHandle: null },
  getClientExtensionResults: () => ({}),
}) as unknown as PublicKeyCredential;

const options = { options: { publicKey: { challenge: "AQID", rp: { id: "localhost", name: "AdminOS" }, user: { id: "BAU", name: "o", displayName: "O" } } } };
const codes = Array.from({ length: 10 }, (_, i) => `AAAA-BBBB-CCCC-000${i}`);

beforeEach(() => {
  vi.stubGlobal("PublicKeyCredential", function PublicKeyCredential() {});
  Object.defineProperty(navigator, "credentials", { configurable: true, value: {} });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const status = (step: string, extra: object = {}) => http.get("/api/v1/auth/passkey/status", () => ok({ step, passkeys: [], recoveryCodesLeft: 0, ...extra }));

/** The passkey step after Google (AOS-617). */
describe("Passkey page", () => {
  it("a verified session goes straight home", async () => {
    server.use(status("done"));
    renderWithQuery(<PasskeyPage />);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/"));
  });

  it("first sign-in: sets up a passkey, shows the recovery codes once, then goes home", async () => {
    let registered: Record<string, unknown> | null = null;
    server.use(
      status("enrol"),
      http.post("/api/v1/auth/passkey/register/options", () => ok(options)),
      http.post("/api/v1/auth/passkey/register", async ({ request }) => { registered = (await request.json()) as Record<string, unknown>; return ok({ recoveryCodes: codes }); }),
    );
    const create = vi.spyOn(ceremony, "create").mockResolvedValue(fakeCredential("create"));
    const { user } = renderWithQuery(<PasskeyPage />);
    await user.click(await screen.findByRole("button", { name: "Set up a passkey" }));
    const list = await screen.findByRole("list", { name: "Recovery codes" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(10);
    expect(create).toHaveBeenCalledOnce();
    expect(registered).toMatchObject({ name: passkeyName(), response: { id: "Bg", response: { attestationObject: "Ag" } } });
    expect(nav.router.replace).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Copy codes" }));
    expect(await navigator.clipboard.readText()).toContain("AAAA-BBBB-CCCC-0000");
    await user.click(screen.getByRole("button", { name: "I've saved them" }));
    expect(nav.router.replace).toHaveBeenCalledWith("/");
  });

  it("later sign-ins: confirm with the passkey and go home", async () => {
    server.use(status("verify"), http.post("/api/v1/auth/passkey/authenticate/options", () => ok(options)), http.post("/api/v1/auth/passkey/authenticate", () => ok({ step: "done" })));
    vi.spyOn(ceremony, "get").mockResolvedValue(fakeCredential("get"));
    const { user } = renderWithQuery(<PasskeyPage />);
    await user.click(await screen.findByRole("button", { name: "Use your passkey" }));
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/"));
  });

  it("a cancelled passkey dialog is explained and can be retried", async () => {
    server.use(status("verify"), http.post("/api/v1/auth/passkey/authenticate/options", () => ok(options)));
    vi.spyOn(ceremony, "get").mockRejectedValue(new DOMException("raw", "NotAllowedError"));
    const { user } = renderWithQuery(<PasskeyPage />);
    await user.click(await screen.findByRole("button", { name: "Use your passkey" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("cancelled or timed out");
    expect(screen.getByRole("button", { name: "Use your passkey" })).toBeEnabled();
  });

  it("a passkey the API refuses is shown in words", async () => {
    server.use(status("verify"), http.post("/api/v1/auth/passkey/authenticate/options", () => ok(options)),
      http.post("/api/v1/auth/passkey/authenticate", () => fail(400, "PASSKEY_001", "The passkey wasn't accepted. Try again.")));
    vi.spyOn(ceremony, "get").mockResolvedValue(fakeCredential("get"));
    const { user } = renderWithQuery(<PasskeyPage />);
    await user.click(await screen.findByRole("button", { name: "Use your passkey" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The passkey wasn't accepted. Try again.");
  });

  it("no credential returned is an error, not a silent success", async () => {
    server.use(status("enrol"), http.post("/api/v1/auth/passkey/register/options", () => ok(options)));
    vi.spyOn(ceremony, "create").mockResolvedValue(null);
    const { user } = renderWithQuery(<PasskeyPage />);
    await user.click(await screen.findByRole("button", { name: "Set up a passkey" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("No passkey was created.");
  });

  it("a lost passkey: a recovery code lets the owner in", async () => {
    let sent = "";
    server.use(status("verify"), http.post("/api/v1/auth/passkey/recover", async ({ request }) => { sent = ((await request.json()) as { code: string }).code; return ok({ step: "done", recoveryCodesLeft: 9 }); }));
    const { user } = renderWithQuery(<PasskeyPage />);
    await user.click(await screen.findByRole("button", { name: "Lost your passkey? Use a recovery code" }));
    const submit = screen.getByRole("button", { name: "Use recovery code" });
    expect(submit).toBeDisabled();
    await user.type(screen.getByLabelText("Recovery code"), "AAAA-BBBB-CCCC-0000");
    await user.click(submit);
    await waitFor(() => expect(nav.router.replace).toHaveBeenCalledWith("/"));
    expect(sent).toBe("AAAA-BBBB-CCCC-0000");
  });

  it("a wrong recovery code is refused", async () => {
    server.use(status("verify"), http.post("/api/v1/auth/passkey/recover", () => fail(400, "PASSKEY_005", "That recovery code isn't valid")));
    const { user } = renderWithQuery(<PasskeyPage />);
    await user.click(await screen.findByRole("button", { name: "Lost your passkey? Use a recovery code" }));
    await user.type(screen.getByLabelText("Recovery code"), "AAAA-BBBB-CCCC-9999");
    await user.click(screen.getByRole("button", { name: "Use recovery code" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That recovery code isn't valid");
  });

  it("a browser without passkeys is told so and can't start", async () => {
    vi.stubGlobal("PublicKeyCredential", undefined);
    server.use(status("enrol"));
    renderWithQuery(<PasskeyPage />);
    expect(await screen.findByRole("button", { name: "Set up a passkey" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("This browser can't use passkeys");
  });

  it("a status that can't load is explained, and signing out is always possible", async () => {
    server.use(http.get("/api/v1/auth/passkey/status", () => fail(500, "SERVER_001", "Internal server error")));
    const { api } = await import("@/lib/api");
    const logout = vi.spyOn(api, "logout").mockResolvedValue();
    const { user } = renderWithQuery(<PasskeyPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Internal server error");
    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(logout).toHaveBeenCalled();
  });
});

describe("Passkey manager (Settings → Security)", () => {
  const key = { id: "k1", name: "Passkey on Mac", createdAt: "2026-10-18T05:00:00Z", lastUsedAt: null };

  it("lists passkeys and recovery codes left", async () => {
    server.use(status("done", { passkeys: [key], recoveryCodesLeft: 7 }));
    renderWithQuery(<PasskeyManager />);
    expect(await screen.findByText("Passkey on Mac")).toBeInTheDocument();
    expect(screen.getByText(/last used never/)).toBeInTheDocument();
    expect(screen.getByText("7 recovery codes left.")).toBeInTheDocument();
  });

  it("adds a second passkey (no new codes)", async () => {
    let calls = 0;
    server.use(status("done", { passkeys: [key] }), http.post("/api/v1/auth/passkey/register/options", () => ok(options)),
      http.post("/api/v1/auth/passkey/register", () => { calls++; return ok({ recoveryCodes: [] }); }));
    vi.spyOn(ceremony, "create").mockResolvedValue(fakeCredential("create"));
    const { user } = renderWithQuery(<PasskeyManager />);
    await user.click(await screen.findByRole("button", { name: "Add a passkey" }));
    await waitFor(() => expect(calls).toBe(1));
    expect(screen.queryByRole("list", { name: "Recovery codes" })).not.toBeInTheDocument();
  });

  it("new recovery codes after a confirmation, shown once", async () => {
    server.use(status("done", { passkeys: [key] }), http.post("/api/v1/auth/passkey/recovery-codes", () => ok({ recoveryCodes: codes })));
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const { user } = renderWithQuery(<PasskeyManager />);
    await user.click(await screen.findByRole("button", { name: "New recovery codes" }));
    expect(await screen.findByRole("list", { name: "Recovery codes" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "I've saved them" }));
    expect(screen.queryByRole("list", { name: "Recovery codes" })).not.toBeInTheDocument();
  });

  it("cancelling, a failure, and a load error are handled", async () => {
    server.use(status("done", { passkeys: [key] }), http.post("/api/v1/auth/passkey/recovery-codes", () => fail(403, "PASSKEY_003", "Confirm with your passkey first.")));
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const { user } = renderWithQuery(<PasskeyManager />);
    await user.click(await screen.findByRole("button", { name: "New recovery codes" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    confirm.mockReturnValue(true);
    await user.click(screen.getByRole("button", { name: "New recovery codes" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Confirm with your passkey first.");
  });

  it("a status that can't load is shown", async () => {
    server.use(http.get("/api/v1/auth/passkey/status", () => fail(500, "SERVER_001", "Internal server error")));
    renderWithQuery(<PasskeyManager />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Internal server error");
  });

  it("names passkeys after the device", () => {
    expect(passkeyName("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)")).toBe("Passkey on iPhone");
    expect(passkeyName("Mozilla/5.0 (Windows NT 10.0)")).toBe("Passkey on Windows");
    expect(passkeyName("curl/8")).toBe("Passkey on this device");
  });
});
