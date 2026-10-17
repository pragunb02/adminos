-- V61 (tentative number; the integrator renumbers): refresh-token rotation with reuse detection and security alerts (AOS-620, AOS-121).
-- session_rotated_tokens: the SHA-256 of every refresh token a session has replaced. Presenting one again means it was
-- copied: the whole session is revoked and the owner is alerted. Rows go with their session.
CREATE TABLE session_rotated_tokens (
  token_hash  VARCHAR(64) PRIMARY KEY,
  session_id  UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  rotated_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_session_rotated_tokens_session ON session_rotated_tokens (session_id);

-- Sign-in alerts: new device, refused attempt, reused session key.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'security';
