import { afterEach, describe, expect, it, vi } from "vitest";
import { creationOptions, credentialJson, fromBase64Url, passkeyErrorMessage, passkeysSupported, requestOptions, toBase64Url } from "./webauthn";

const bytes = (...b: number[]) => new Uint8Array(b).buffer;

afterEach(() => vi.unstubAllGlobals());

/** Browser ↔ API passkey JSON (AOS-617). */
describe("webauthn helpers", () => {
  it("base64url round-trips every byte value, without padding", () => {
    const all = new Uint8Array(256).map((_, i) => i);
    const text = toBase64Url(all);
    expect(text).not.toMatch(/[+/=]/);
    expect(new Uint8Array(fromBase64Url(text))).toEqual(all);
    expect(toBase64Url(new Uint8Array([251, 255]).subarray(1))).toBe("_w");
    expect(new Uint8Array(fromBase64Url("_w"))).toEqual(new Uint8Array([255]));
  });

  it("creation options get binary challenge, user id and excluded ids", () => {
    const o = creationOptions({ publicKey: { challenge: "AQID", rp: { id: "localhost", name: "AdminOS" }, user: { id: "BAU", name: "o", displayName: "O" }, excludeCredentials: [{ type: "public-key", id: "Bg" }] } });
    const pk = o.publicKey!;
    expect(new Uint8Array(pk.challenge as ArrayBuffer)).toEqual(new Uint8Array([1, 2, 3]));
    expect(new Uint8Array(pk.user.id as ArrayBuffer)).toEqual(new Uint8Array([4, 5]));
    expect(new Uint8Array(pk.excludeCredentials![0].id as ArrayBuffer)).toEqual(new Uint8Array([6]));
    expect(pk.rp).toEqual({ id: "localhost", name: "AdminOS" });
  });

  it("request options get a binary challenge and allowed ids; none listed stays undefined", () => {
    const o = requestOptions({ publicKey: { challenge: "AQID", rpId: "localhost", allowCredentials: [{ type: "public-key", id: "Bg" }] } });
    expect(new Uint8Array(o.publicKey!.challenge as ArrayBuffer)).toEqual(new Uint8Array([1, 2, 3]));
    expect(new Uint8Array(o.publicKey!.allowCredentials![0].id as ArrayBuffer)).toEqual(new Uint8Array([6]));
    expect(requestOptions({ publicKey: { challenge: "AQ" } }).publicKey!.allowCredentials).toBeUndefined();
    expect(() => requestOptions({})).toThrow();
  });

  it("a registration credential becomes the JSON the API verifies", () => {
    const cred = {
      type: "public-key", id: "Bg", rawId: bytes(6),
      response: { clientDataJSON: bytes(1), attestationObject: bytes(2), getTransports: () => ["internal"] },
      getClientExtensionResults: () => ({ credProps: { rk: true } }),
    } as unknown as PublicKeyCredential;
    expect(credentialJson(cred)).toEqual({
      type: "public-key", id: "Bg", rawId: "Bg",
      response: { clientDataJSON: "AQ", attestationObject: "Ag", transports: ["internal"] },
      clientExtensionResults: { credProps: { rk: true } },
    });
  });

  it("an assertion credential becomes the JSON the API verifies", () => {
    const cred = {
      type: "public-key", id: "Bg", rawId: bytes(6),
      response: { clientDataJSON: bytes(1), authenticatorData: bytes(3), signature: bytes(4), userHandle: bytes(5) },
    } as unknown as PublicKeyCredential;
    expect(credentialJson(cred)).toEqual({
      type: "public-key", id: "Bg", rawId: "Bg",
      response: { clientDataJSON: "AQ", authenticatorData: "Aw", signature: "BA", userHandle: "BQ" },
      clientExtensionResults: {},
    });
    const noHandle = { ...cred, response: { ...(cred.response as object), userHandle: null } } as unknown as PublicKeyCredential;
    expect((credentialJson(noHandle).response as Record<string, unknown>).userHandle).toBeUndefined();
  });

  it("support is detected from the browser's APIs", () => {
    vi.stubGlobal("PublicKeyCredential", undefined);
    expect(passkeysSupported()).toBe(false);
    vi.stubGlobal("PublicKeyCredential", function PublicKeyCredential() {});
    Object.defineProperty(navigator, "credentials", { configurable: true, value: {} });
    expect(passkeysSupported()).toBe(true);
  });

  it.each([
    ["NotAllowedError", "cancelled or timed out"],
    ["InvalidStateError", "already set up"],
    ["SecurityError", "its own address"],
    ["NotSupportedError", "can't create a passkey"],
  ])("%s is explained in words", (name, text) => {
    expect(passkeyErrorMessage(new DOMException("raw browser text", name))).toContain(text);
  });

  it("other errors keep their message, or a fallback", () => {
    expect(passkeyErrorMessage(new Error("The passkey wasn't accepted. Try again."))).toBe("The passkey wasn't accepted. Try again.");
    expect(passkeyErrorMessage("weird")).toBe("The passkey didn't work. Try again.");
  });
});
