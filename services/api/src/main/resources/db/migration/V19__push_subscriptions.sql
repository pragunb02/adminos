-- V19: Web Push subscriptions (AOS-510). One row per device (endpoint); the keys encrypt messages to it.
-- Endpoints identify a device, so they are never logged; a device can belong to one user at a time.
CREATE TABLE push_subscriptions (
  id               UUID PRIMARY KEY,
  user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  endpoint         VARCHAR(2048) NOT NULL UNIQUE,
  p256dh           VARCHAR(128) NOT NULL,
  auth             VARCHAR(64) NOT NULL,
  label            VARCHAR(100),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_success_at  TIMESTAMPTZ
);

CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);
