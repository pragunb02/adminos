-- AI layer (AOS-702, D-67): the audit log of model calls and the monthly spend ledger behind the cost cap.

-- One row per provider call. No content: no prompt, no model output, no message text, no merchant name.
-- `code` is a short machine code ([a-z0-9_]) for why a call was rejected or failed; NULL when accepted.
CREATE TABLE ai_calls (
  id             UUID PRIMARY KEY,
  user_id        UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  feature        VARCHAR(40)  NOT NULL,
  provider       VARCHAR(20)  NOT NULL,
  model          VARCHAR(100) NOT NULL,
  prompt_version VARCHAR(100) NOT NULL,
  input_tokens   BIGINT       NOT NULL CHECK (input_tokens >= 0),
  output_tokens  BIGINT       NOT NULL CHECK (output_tokens >= 0),
  cost_micros    BIGINT       NOT NULL CHECK (cost_micros >= 0),
  outcome        VARCHAR(10)  NOT NULL CHECK (outcome IN ('accepted', 'rejected', 'error')),
  code           VARCHAR(48)  CHECK (code ~ '^[a-z0-9_]{1,48}$'),
  latency_ms     BIGINT       NOT NULL CHECK (latency_ms >= 0),
  created_at     TIMESTAMPTZ  NOT NULL,
  CHECK ((outcome = 'accepted') = (code IS NULL))
);
CREATE INDEX ai_calls_user_feature_time ON ai_calls (user_id, feature, created_at);

-- One row per UTC month. A call reserves its worst-case cost against the cap under a row lock, then settles
-- to its real cost, so concurrent calls and several API instances can't overshoot the cap.
CREATE TABLE ai_spend (
  month           CHAR(7)     PRIMARY KEY CHECK (month ~ '^[0-9]{4}-[0-9]{2}$'),
  spent_micros    BIGINT      NOT NULL DEFAULT 0 CHECK (spent_micros >= 0),
  reserved_micros BIGINT      NOT NULL DEFAULT 0 CHECK (reserved_micros >= 0),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
