-- 008: Extraction quality — per-sync confidence analytics

CREATE TABLE extraction_quality (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sync_session_id   UUID REFERENCES sync_sessions(id) ON DELETE SET NULL,
  source_type       source_type NOT NULL,
  bank_code         VARCHAR(20),
  total_records     INTEGER NOT NULL DEFAULT 0,
  high_confidence   INTEGER NOT NULL DEFAULT 0,
  medium_confidence INTEGER NOT NULL DEFAULT 0,
  low_confidence    INTEGER NOT NULL DEFAULT 0,
  needs_review      INTEGER NOT NULL DEFAULT 0,
  ai_enriched       INTEGER NOT NULL DEFAULT 0,
  avg_confidence    NUMERIC(3,2),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_eq_user ON extraction_quality (user_id);
CREATE INDEX idx_eq_source ON extraction_quality (source_type, bank_code);
CREATE INDEX idx_eq_session ON extraction_quality (sync_session_id);
