# Passkeys: the second factor after Google

_AOS-617 · Proposed decision D-96 · 2026-10-18_

AdminOS asks for **Google and then a passkey**. A stolen or phished Google sign-in alone opens nothing: the session it
creates can only reach the passkey step (`/auth/passkey`); every other API call answers `AUTH_010` until the session
is confirmed with a passkey or a recovery code.

## How it works
- **Passkey** = a WebAuthn credential (Face ID / Touch ID / Windows Hello / Android / a security key). The private key
  never leaves the device; AdminOS stores only the public key. User verification (biometric or PIN) is **required**.
- **Relying party:** the RP ID is `APP_URL`'s host and the only accepted origin is `APP_URL`'s origin. Changing
  `APP_URL` (e.g. moving to a new Tailscale name) makes existing passkeys stop working: enrol again (see *Reset*).
- **Library:** Yubico `java-webauthn-server` 2.10.1 (BSD-2-Clause), which checks challenge, origin, RP ID, flags,
  signature and the signature counter. Attestation is not requested.

## First sign-in (bootstrap: how the owner can't lock themselves out)
1. Sign in with Google. With **no passkey enrolled yet**, the page offers *Set up a passkey*.
2. Create it. The session is now confirmed, and **ten recovery codes are shown once**.
3. Save the codes outside this device (password manager or paper). Then add a **second passkey** (another device or
   a security key) from Settings → Security, so losing one device doesn't lock you out.

Risk accepted for the bootstrap: whoever completes the *first* Google sign-in can enrol the first passkey. So do the
first sign-in yourself right after deploying, and watch for the "A passkey was added" alert. Once one passkey exists,
a session that hasn't passed the passkey step can't add another.

## Every later sign-in
Google, then *Use your passkey*. Lost the device? *Use a recovery code* (each works once; you are alerted, and the
number left is shown in Settings → Security). Make new codes there at any time (the old ones stop working).

Five failed passkey or recovery-code attempts on one sign-in end that session and alert you.

## Alerts you get
New sign-in · refused sign-in · passkey added · recovery code used · passkey checks failed · copied session key.

## Reset (everything lost)
Only possible with access to the server, by design (an attacker with only your Google account can't do it):

```sql
-- psql on the AdminOS database (docker compose exec postgres psql -U adminos adminos)
BEGIN;
DELETE FROM recovery_codes;
DELETE FROM webauthn_credentials;
UPDATE sessions SET revoked_at = now() WHERE revoked_at IS NULL;
COMMIT;
```

Then sign in with Google and enrol a new passkey straight away (the bootstrap above).

## Browser support and test limits
- Works in current Safari (iOS/macOS), Chrome, Edge and Firefox on `https://` or `http://localhost`. WebAuthn refuses
  IP addresses as RP IDs, so open AdminOS by its name, not `127.0.0.1`.
- Automated tests: the Kotlin tests drive the real verification with a software P-256 authenticator (every forgery:
  wrong origin, RP ID, challenge, type, key, user handle; missing user verification; counter rollback; replay;
  expiry). Playwright drives the browser side with **Chromium's virtual authenticator** (CDP). **WebKit has no virtual
  authenticator in Playwright**, so iPhone/Safari is checked by hand: enrol on an iPhone, sign out, sign in again
  with Face ID, and use one recovery code.
