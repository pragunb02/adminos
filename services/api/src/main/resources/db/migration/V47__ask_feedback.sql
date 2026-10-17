-- V47: "Ask your money" feedback for the eval set (AOS-711).
-- One row per rated answer. The masked question and the model's plan are one encrypted payload (AES-GCM,
-- TokenEncryptor, bound to the row id), so the database holds no readable question. No amounts, no answer text.
CREATE TABLE ask_feedback (
  ask_id          UUID PRIMARY KEY,
  user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  helpful         BOOLEAN     NOT NULL,
  source          VARCHAR(16) NOT NULL CHECK (source IN ('model', 'template', 'declined', 'unanswered')),
  prompt_version  VARCHAR(32) NOT NULL CHECK (prompt_version ~ '^[a-z0-9._-]{1,32}$'),
  payload         TEXT        NOT NULL CHECK (payload LIKE 'v2:%' AND length(payload) <= 16384),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ask_feedback_user ON ask_feedback (user_id, created_at DESC);
