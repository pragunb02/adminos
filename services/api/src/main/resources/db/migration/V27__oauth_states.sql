-- V27: pending OAuth consents (AOS-406, D-64): the CSRF `state` sent to Google, checked once.
-- state_hash:    SHA-256 (hex) of the state; the state itself is only in the user's browser.
-- purpose:       what it was issued for (gmail_connect); accepted only for the same purpose.
-- redirect_uri:  where Google sends the user back; the connect call must present the same one.
-- code_verifier: the PKCE verifier, encrypted with TOKEN_ENCRYPTION_KEY (context oauth_states:<id>:code_verifier).
-- expires_at:    10 minutes after creation; used_at makes it single-use.
-- Rows are deleted a day after they expire.
CREATE TABLE oauth_states (
  id            UUID PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose       VARCHAR(30) NOT NULL,
  state_hash    VARCHAR(64) NOT NULL UNIQUE,
  redirect_uri  TEXT NOT NULL,
  code_verifier TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL,
  used_at       TIMESTAMPTZ
);
CREATE INDEX idx_oauth_states_expires ON oauth_states (expires_at);
