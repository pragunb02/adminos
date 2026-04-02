-- 005: Agent domain — briefings, insights, anomalies

CREATE TABLE briefings (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  period_start      DATE NOT NULL,
  period_end        DATE NOT NULL,
  type              briefing_type NOT NULL DEFAULT 'weekly',
  content           TEXT NOT NULL,
  content_structured JSONB,
  total_spent       NUMERIC(12,2),
  total_income      NUMERIC(12,2),
  top_categories    JSONB,
  subscriptions_flagged INTEGER NOT NULL DEFAULT 0,
  anomalies_detected   INTEGER NOT NULL DEFAULT 0,
  bills_upcoming    INTEGER NOT NULL DEFAULT 0,
  status            briefing_status NOT NULL DEFAULT 'generated',
  delivered_at      TIMESTAMPTZ,
  opened_at         TIMESTAMPTZ,
  model_used        VARCHAR(50) NOT NULL,
  prompt_version    VARCHAR(20) NOT NULL,
  tokens_used       INTEGER NOT NULL,
  generation_ms     INTEGER,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE briefings ADD CONSTRAINT uq_briefing UNIQUE (user_id, period_start, type);
CREATE INDEX idx_brief_user ON briefings (user_id, created_at DESC);

CREATE TABLE insights (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  briefing_id       UUID REFERENCES briefings(id) ON DELETE SET NULL,
  type              insight_type NOT NULL,
  title             VARCHAR(255) NOT NULL,
  body              TEXT NOT NULL,
  severity          severity NOT NULL DEFAULT 'info',
  entity_type       VARCHAR(50),
  entity_id         UUID,
  action_type       insight_action_type NOT NULL DEFAULT 'none',
  action_payload    JSONB,
  status            insight_status NOT NULL DEFAULT 'pending',
  seen_at           TIMESTAMPTZ,
  acted_at          TIMESTAMPTZ,
  dismissed_at      TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_insight_user ON insights (user_id, status);
CREATE INDEX idx_insight_created ON insights (user_id, created_at DESC);

CREATE TABLE anomalies (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  transaction_id    UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
  type              anomaly_type NOT NULL,
  confidence_score  NUMERIC(3,2) NOT NULL,
  reason            TEXT NOT NULL,
  agent_explanation TEXT,
  status            anomaly_status NOT NULL DEFAULT 'open',
  resolved_at       TIMESTAMPTZ,
  resolved_by       anomaly_resolver,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE anomalies ADD CONSTRAINT chk_anom_conf CHECK (confidence_score >= 0.0 AND confidence_score <= 1.0);
CREATE INDEX idx_anom_user ON anomalies (user_id, status);
CREATE INDEX idx_anom_open ON anomalies (user_id, created_at DESC) WHERE status = 'open';
