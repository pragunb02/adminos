-- 007: Audit domain — audit_logs, events

CREATE TABLE audit_logs (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID REFERENCES users(id) ON DELETE SET NULL,
  actor             actor_type NOT NULL,
  action            VARCHAR(100) NOT NULL,
  entity_type       VARCHAR(50) NOT NULL,
  entity_id         UUID,
  before_state      JSONB,
  after_state       JSONB,
  ip_address        INET,
  device_id         UUID,
  metadata          JSONB,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_user ON audit_logs (user_id, created_at DESC);
CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_action ON audit_logs (action, created_at DESC);

CREATE TABLE events (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID REFERENCES users(id) ON DELETE SET NULL,
  event_type        VARCHAR(100) NOT NULL,
  properties        JSONB,
  session_id        UUID,
  device_id         UUID,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_user ON events (user_id, event_type);
CREATE INDEX idx_events_type ON events (event_type, created_at DESC);
