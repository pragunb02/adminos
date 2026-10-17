-- V35: spending limits and their alerts (AOS-904, D-80, D-83).
-- Coach pushes are their own notification type, gated by coach_settings.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'coach';

-- One limit per (user, scope, period). scope_value: a lens id, a merchant key (trimmed, lower case) or a category.
CREATE TABLE spending_limits (
  id          UUID          PRIMARY KEY,
  user_id     UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  scope_kind  VARCHAR(10)   NOT NULL CHECK (scope_kind IN ('lens', 'merchant', 'category')),
  scope_value VARCHAR(255)  NOT NULL CHECK (scope_value <> ''),
  label       VARCHAR(255)  NOT NULL,
  period      VARCHAR(10)   NOT NULL CHECK (period IN ('weekly', 'monthly')),
  amount      NUMERIC(12,2) NOT NULL CHECK (amount > 0 AND amount <= 10000000),
  created_at  TIMESTAMPTZ   NOT NULL,
  updated_at  TIMESTAMPTZ   NOT NULL,
  UNIQUE (user_id, scope_kind, scope_value, period)
);

-- The ledger: a threshold announced (or silently passed) once per limit and period. The primary key is the claim.
CREATE TABLE limit_alerts (
  limit_id     UUID        NOT NULL REFERENCES spending_limits(id) ON DELETE CASCADE,
  period_start DATE        NOT NULL,
  threshold    SMALLINT    NOT NULL CHECK (threshold IN (50, 80, 100)),
  claimed_at   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (limit_id, period_start, threshold)
);

-- "Not now" on a suggested lens limit.
CREATE TABLE limit_suggestion_dismissals (
  user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  lens_id      VARCHAR(40) NOT NULL,
  dismissed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, lens_id)
);

-- The coach's switches (one row per user; missing = defaults).
CREATE TABLE coach_settings (
  user_id      UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  limit_alerts BOOLEAN     NOT NULL DEFAULT TRUE,
  updated_at   TIMESTAMPTZ NOT NULL
);
