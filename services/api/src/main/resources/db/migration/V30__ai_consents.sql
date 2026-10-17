-- AI consent per feature (AOS-704, D-67; DPDP s.6: consent per purpose). No row means the feature is off for
-- that user. Changes are also written to audit_logs (action ai.consent) as evidence.
CREATE TABLE ai_consents (
  user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  feature    VARCHAR(40) NOT NULL CHECK (feature ~ '^[a-z][a-z0-9_-]{0,39}$'),
  enabled    BOOLEAN     NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, feature)
);
