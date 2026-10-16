-- V12: Scoped, revocable device tokens (AOS-203). The token itself is a signed JWT (typ=device)
-- whose jti is this row's id. Only metadata is stored; the token is shown to the user once.
CREATE TABLE device_tokens (
  id            UUID PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name          VARCHAR(100) NOT NULL,
  scopes        TEXT NOT NULL,           -- space-separated, e.g. 'ingest:sms'
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NOT NULL,
  last_used_at  TIMESTAMPTZ,
  revoked_at    TIMESTAMPTZ
);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);
