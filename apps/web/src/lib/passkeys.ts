/**
 * The passkey step and passkey management (AOS-617), over the API's `/api/v1/auth/passkey/*`.
 * The browser ceremony (`navigator.credentials`) is injectable so tests can stand in for the authenticator.
 */
import { getData, postData } from "./query";
import { creationOptions, credentialJson, requestOptions } from "./webauthn";

export type PasskeyStep = "enrol" | "verify" | "done";

export interface PasskeyInfo {
  id: string;
  name: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface PasskeyStatus {
  step: PasskeyStep;
  passkeys: PasskeyInfo[];
  recoveryCodesLeft: number;
}

/** The browser's WebAuthn calls, replaceable in tests. */
export const ceremony = {
  create: (o: CredentialCreationOptions) => navigator.credentials.create(o) as Promise<PublicKeyCredential | null>,
  get: (o: CredentialRequestOptions) => navigator.credentials.get(o) as Promise<PublicKeyCredential | null>,
};

const BASE = "/api/v1/auth/passkey";

export const passkeyStatus = () => getData<PasskeyStatus>(`${BASE}/status`);

/** A coarse name for the new passkey, e.g. "Passkey on iPhone" (the owner sees it in Settings → Security). */
export function passkeyName(ua: string = typeof navigator === "undefined" ? "" : navigator.userAgent): string {
  const where = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Android/.test(ua) ? "Android" : /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : "this device";
  return `Passkey on ${where}`;
}

/** Creates a passkey and registers it. Returns the recovery codes (only on the first passkey). */
export async function enrolPasskey(): Promise<string[]> {
  const { options } = await postData<{ options: Record<string, unknown> }>(`${BASE}/register/options`, {});
  const credential = await ceremony.create(creationOptions(options));
  if (!credential) throw new Error("No passkey was created.");
  const done = await postData<{ recoveryCodes: string[] }>(`${BASE}/register`, { response: credentialJson(credential), name: passkeyName() });
  return done.recoveryCodes;
}

/** Proves this session with an existing passkey. */
export async function verifyPasskey(): Promise<void> {
  const { options } = await postData<{ options: Record<string, unknown> }>(`${BASE}/authenticate/options`, {});
  const credential = await ceremony.get(requestOptions(options));
  if (!credential) throw new Error("No passkey was used.");
  await postData(`${BASE}/authenticate`, { response: credentialJson(credential) });
}

export const redeemRecoveryCode = (code: string) => postData<{ recoveryCodesLeft: number }>(`${BASE}/recover`, { code });

export const newRecoveryCodes = () => postData<{ recoveryCodes: string[] }>(`${BASE}/recovery-codes`, {}).then((r) => r.recoveryCodes);
