import type { CDPSession, Page } from "@playwright/test";
import { expect, test } from "./support/fixtures";

/**
 * Passkeys in a real browser (AOS-617), with Chromium's virtual authenticator (CDP `WebAuthn` domain): the page really
 * calls `navigator.credentials.create/get`, and the API (mocked here; the real verification is covered by the Kotlin
 * tests with a software authenticator) receives what a real passkey would send.
 *
 * WebKit limits: Playwright's WebKit has no virtual authenticator (no CDP), so these run on the Chromium projects
 * only; Safari behaviour is checked by hand on an iPhone (see docs/security/PASSKEYS.md). WebAuthn also refuses IP
 * addresses as relying-party IDs, so these tests use `localhost` instead of 127.0.0.1.
 */
test.skip(({ browserName }) => browserName !== "chromium", "needs Chromium's virtual authenticator (CDP)");

const b64url = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64url");
const random = (n: number) => b64url(crypto.getRandomValues(new Uint8Array(n)));

async function virtualAuthenticator(page: Page): Promise<{ cdp: CDPSession; authenticatorId: string }> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("WebAuthn.enable");
  const { authenticatorId } = await cdp.send("WebAuthn.addVirtualAuthenticator", {
    options: { protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
  });
  return { cdp, authenticatorId };
}

const creation = (challenge: string) => ({
  options: {
    publicKey: {
      challenge, rp: { name: "AdminOS", id: "localhost" }, user: { id: random(16), name: "owner@example.com", displayName: "Owner" },
      pubKeyCredParams: [{ type: "public-key", alg: -7 }], authenticatorSelection: { residentKey: "preferred", userVerification: "required" },
      timeout: 60_000, attestation: "none",
    },
  },
});
const request = (challenge: string, id: string) => ({
  options: { publicKey: { challenge, rpId: "localhost", allowCredentials: [{ type: "public-key", id }], userVerification: "required", timeout: 60_000 } },
});

const clientData = (body: unknown) => {
  const r = (body as { response: { response: { clientDataJSON: string } } }).response.response;
  return JSON.parse(Buffer.from(r.clientDataJSON, "base64url").toString());
};

test.describe("passkey step", () => {
  test.beforeEach(async ({ context, baseURL }) => {
    // Same server, but on a name WebAuthn accepts as a relying-party ID.
    await context.addCookies([{ name: "access_token", value: "e2e-session", url: baseURL!.replace("127.0.0.1", "localhost") }]);
  });

  test("first sign-in creates a passkey with the browser's authenticator, then shows recovery codes once", async ({ signedIn: page, api, baseURL }) => {
    const local = baseURL!.replace("127.0.0.1", "localhost");
    const { cdp, authenticatorId } = await virtualAuthenticator(page);
    const challenge = random(32);
    api.on("GET /api/v1/auth/passkey/status", () => ({ data: { step: "enrol", passkeys: [], recoveryCodesLeft: 0 } }));
    api.on("POST /api/v1/auth/passkey/register/options", () => ({ data: creation(challenge) }));
    api.on("POST /api/v1/auth/passkey/register", () => ({ status: 201, data: { recoveryCodes: Array.from({ length: 10 }, (_, i) => `AAAA-BBBB-CCCC-000${i}`), step: "done" } }));

    await page.goto(`${local}/auth/passkey`);
    await page.getByRole("button", { name: "Set up a passkey" }).click();
    await expect(page.getByRole("list", { name: "Recovery codes" }).getByRole("listitem")).toHaveCount(10);

    await expect.poll(() => api.callsTo("POST", "/api/v1/auth/passkey/register").length).toBe(1);
    const sent = api.callsTo("POST", "/api/v1/auth/passkey/register")[0].body;
    expect(clientData(sent)).toMatchObject({ type: "webauthn.create", challenge, origin: local });
    expect((sent as { response: { response: { attestationObject?: string } } }).response.response.attestationObject).toBeTruthy();
    const { credentials } = await cdp.send("WebAuthn.getCredentials", { authenticatorId });
    expect(credentials).toHaveLength(1);
    expect(credentials[0].rpId).toBe("localhost");
  });

  test("a later sign-in confirms with that passkey and opens the app", async ({ signedIn: page, api, baseURL }) => {
    const local = baseURL!.replace("127.0.0.1", "localhost");
    const { cdp, authenticatorId } = await virtualAuthenticator(page);
    // Enrol first (the authenticator keeps the key), then verify with it.
    api.on("GET /api/v1/auth/passkey/status", () => ({ data: { step: "enrol", passkeys: [], recoveryCodesLeft: 0 } }));
    api.on("POST /api/v1/auth/passkey/register/options", () => ({ data: creation(random(32)) }));
    api.on("POST /api/v1/auth/passkey/register", () => ({ status: 201, data: { recoveryCodes: [], step: "done" } }));
    await page.goto(`${local}/auth/passkey`);
    await page.getByRole("button", { name: "Set up a passkey" }).click();
    await expect.poll(async () => (await cdp.send("WebAuthn.getCredentials", { authenticatorId })).credentials.length).toBe(1);
    const credentialId = Buffer.from((await cdp.send("WebAuthn.getCredentials", { authenticatorId })).credentials[0].credentialId, "base64").toString("base64url");

    const challenge = random(32);
    api.on("GET /api/v1/auth/passkey/status", () => ({ data: { step: "verify", passkeys: [], recoveryCodesLeft: 10 } }));
    api.on("POST /api/v1/auth/passkey/authenticate/options", () => ({ data: request(challenge, credentialId) }));
    api.on("POST /api/v1/auth/passkey/authenticate", () => ({ data: { step: "done" } }));
    await page.goto(`${local}/auth/passkey`);
    await page.getByRole("button", { name: "Use your passkey" }).click();
    await expect(page).toHaveURL(new RegExp(`^${local}/$`));

    const sent = api.callsTo("POST", "/api/v1/auth/passkey/authenticate")[0].body as { response: { id: string; response: { signature?: string } } };
    expect(clientData(sent)).toMatchObject({ type: "webauthn.get", challenge, origin: local });
    expect(sent.response.id).toBe(credentialId);
    expect(sent.response.response.signature).toBeTruthy();
  });

  test("when the device can't verify the owner (no biometric/PIN), nothing is sent and the page says why", async ({ signedIn: page, api, baseURL }) => {
    const local = baseURL!.replace("127.0.0.1", "localhost");
    const { cdp, authenticatorId } = await virtualAuthenticator(page);
    await cdp.send("WebAuthn.setUserVerified", { authenticatorId, isUserVerified: false });
    api.on("GET /api/v1/auth/passkey/status", () => ({ data: { step: "enrol", passkeys: [], recoveryCodesLeft: 0 } }));
    api.on("POST /api/v1/auth/passkey/register/options", () => ({ data: creation(random(32)) }));
    await page.goto(`${local}/auth/passkey`);
    await page.getByRole("button", { name: "Set up a passkey" }).click();
    await expect(page.getByRole("alert").and(page.locator(":not(#__next-route-announcer__)"))).toContainText("cancelled or timed out");
    expect(api.callsTo("POST", "/api/v1/auth/passkey/register")).toHaveLength(0);
  });

  test("any app call that the API answers AUTH_010 sends the browser to the passkey step", async ({ signedIn: page, api }) => {
    api.on("GET /api/v1/dashboard/home", () => ({ status: 401, error: { code: "AUTH_010", message: "Confirm it's you with your passkey" } }));
    api.on("GET /api/v1/auth/passkey/status", () => ({ data: { step: "verify", passkeys: [], recoveryCodesLeft: 10 } }));
    await page.goto("/");
    await expect(page).toHaveURL(/\/auth\/passkey$/);
    await expect(page.getByRole("button", { name: "Use your passkey" })).toBeVisible();
  });
});
