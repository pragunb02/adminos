-- V26: Gmail push watch state on the connection (AOS-405, D-63).
-- users.watch makes Gmail publish mailbox changes to our Pub/Sub topic; it lapses after at most
-- 7 days unless renewed, so the API records what Gmail answered and renews it before then.
-- watch_expires_at: when Gmail stops publishing (the old, never-written pubsub_expiry column).
-- watch_history_id: the mailbox position Gmail reported when the watch was set (informational;
--   syncs still start from history_id, which only a successful sync moves, D-59).
-- watch_error:      why the last registration failed (a short, content-free reason), or NULL.
-- watch_checked_at: when registration was last attempted, successful or not.
ALTER TABLE user_connections RENAME COLUMN pubsub_expiry TO watch_expires_at;
ALTER TABLE user_connections
  ADD COLUMN watch_history_id VARCHAR(100),
  ADD COLUMN watch_error TEXT,
  ADD COLUMN watch_checked_at TIMESTAMPTZ;
