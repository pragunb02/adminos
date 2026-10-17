/**
 * Passkeys in the browser (AOS-617). The API sends WebAuthn options as JSON with binary values in base64url (the
 * `{ publicKey: … }` shape of the WebAuthn JSON serialization); the browser API wants ArrayBuffers and returns them.
 * These helpers convert both ways without depending on the newest `PublicKeyCredential.parse…FromJSON` APIs, which
 * older Safari and Firefox versions lack.
 */

type Json = Record<string, unknown>;

export function fromBase64Url(value: string): ArrayBuffer {
  const b64 = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(bin.length));
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

export function toBase64Url(buffer: ArrayBuffer | ArrayBufferView): string {
  const bytes = buffer instanceof ArrayBuffer ? new Uint8Array(buffer) : new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const withIds = (list: unknown) =>
  Array.isArray(list) ? list.map((c: Json) => ({ ...c, id: fromBase64Url(String(c.id)) })) : undefined;

/** `{ publicKey }` creation options from the API → what `navigator.credentials.create` takes. */
export function creationOptions(options: Json): CredentialCreationOptions {
  const pk = (options.publicKey ?? {}) as Json;
  const user = (pk.user ?? {}) as Json;
  return {
    publicKey: {
      ...(pk as unknown as PublicKeyCredentialCreationOptions),
      challenge: fromBase64Url(String(pk.challenge)),
      user: { ...(user as unknown as PublicKeyCredentialUserEntity), id: fromBase64Url(String(user.id)) },
      excludeCredentials: withIds(pk.excludeCredentials) as PublicKeyCredentialDescriptor[] | undefined,
    },
  };
}

/** `{ publicKey }` request options from the API → what `navigator.credentials.get` takes. */
export function requestOptions(options: Json): CredentialRequestOptions {
  const pk = (options.publicKey ?? {}) as Json;
  return {
    publicKey: {
      ...(pk as unknown as PublicKeyCredentialRequestOptions),
      challenge: fromBase64Url(String(pk.challenge)),
      allowCredentials: withIds(pk.allowCredentials) as PublicKeyCredentialDescriptor[] | undefined,
    },
  };
}

/** A new or used credential → the JSON the API verifies (the WebAuthn JSON serialization). */
export function credentialJson(credential: PublicKeyCredential): Json {
  const r = credential.response as AuthenticatorAttestationResponse & AuthenticatorAssertionResponse;
  const response: Json = { clientDataJSON: toBase64Url(r.clientDataJSON) };
  if ("attestationObject" in r && r.attestationObject) {
    response.attestationObject = toBase64Url(r.attestationObject);
    if (typeof r.getTransports === "function") response.transports = r.getTransports();
  }
  if ("authenticatorData" in r && r.authenticatorData) {
    response.authenticatorData = toBase64Url(r.authenticatorData);
    response.signature = toBase64Url(r.signature);
    if (r.userHandle) response.userHandle = toBase64Url(r.userHandle);
  }
  return {
    type: credential.type,
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    response,
    clientExtensionResults: typeof credential.getClientExtensionResults === "function" ? credential.getClientExtensionResults() : {},
  };
}

/** Whether this browser can do passkeys at all. */
export const passkeysSupported = () =>
  typeof window !== "undefined" && typeof window.PublicKeyCredential !== "undefined" && !!navigator.credentials;

/** Words for what went wrong in the browser's own passkey dialog (never the raw error text). */
export function passkeyErrorMessage(e: unknown): string {
  const name = e instanceof DOMException || e instanceof Error ? e.name : "";
  if (name === "NotAllowedError") return "The passkey request was cancelled or timed out. Try again.";
  if (name === "InvalidStateError") return "This passkey is already set up here.";
  if (name === "SecurityError") return "Passkeys need AdminOS to be opened at its own address (APP_URL).";
  if (name === "NotSupportedError") return "This device can't create a passkey.";
  return e instanceof Error && e.message ? e.message : "The passkey didn't work. Try again.";
}
