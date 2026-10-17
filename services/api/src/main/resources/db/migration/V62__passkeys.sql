-- V62 (tentative number; the integrator renumbers): passkeys as the second factor after Google (AOS-617).
-- sessions.second_factor_at: when the session passed the passkey (or a recovery code). NULL = pending: only the
-- passkey routes accept it.
ALTER TABLE sessions ADD COLUMN second_factor_at TIMESTAMPTZ;

-- webauthn_credentials: public keys only (the private key never leaves the authenticator). credential_id and
-- public_key_cose are base64url. signature_count is checked for clones by the WebAuthn library.
CREATE TABLE webauthn_credentials (
  id               UUID PRIMARY KEY,
  user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  credential_id    VARCHAR(1400) NOT NULL UNIQUE,
  public_key_cose  TEXT NOT NULL,
  signature_count  BIGINT NOT NULL CHECK (signature_count >= 0),
  name             VARCHAR(60) NOT NULL,
  transports       TEXT NOT NULL DEFAULT '',
  created_at       TIMESTAMPTZ NOT NULL,
  last_used_at     TIMESTAMPTZ
);
CREATE INDEX idx_webauthn_credentials_user ON webauthn_credentials (user_id);

-- recovery_codes: SHA-256 (hex) of each one-time code; used_at makes it single-use.
CREATE TABLE recovery_codes (
  id          UUID PRIMARY KEY,
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code_hash   VARCHAR(64) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ,
  UNIQUE (user_id, code_hash)
);
