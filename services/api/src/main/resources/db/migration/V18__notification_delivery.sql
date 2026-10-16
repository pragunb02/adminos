-- V18: Notification delivery state (AOS-504).
-- deliver_after: a PENDING notification is not sent before this (quiet-hours end, retry backoff, claim lease).
-- attempts / last_error: retries are bounded and the reason for a failure is kept (no personal data).
ALTER TABLE notifications
  ADD COLUMN deliver_after TIMESTAMPTZ,
  ADD COLUMN attempts      INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  ADD COLUMN last_error    TEXT;

CREATE INDEX idx_notif_due ON notifications (deliver_after, created_at) WHERE status = 'pending';
