-- V32: Google sign-in uses a server-side state + PKCE too (AOS-618, AUD-06).
-- A sign-in state is issued before anyone is signed in, so it has no user. Every other purpose still needs one.
ALTER TABLE oauth_states ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE oauth_states ADD CONSTRAINT oauth_states_user_unless_login
  CHECK ((purpose = 'login') = (user_id IS NULL));
