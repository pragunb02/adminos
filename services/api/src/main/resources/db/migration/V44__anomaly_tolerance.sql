-- AOS-713 (D-73): robust anomaly baselines and learning from the user's answers.
--
-- anomalies.rules_fired: the rules that fired, comma-separated (e.g. 'large_amount,odd_hours'), so an
--   answer knows what to relax. NULL on rows from before this migration (their type says enough).
-- anomalies.merchant_key: the merchant the spend was judged at (trimmed, lower-cased name), fixed when
--   detected so a later rename can't move the feedback to another merchant.
ALTER TABLE anomalies
  ADD COLUMN rules_fired  TEXT,
  ADD COLUMN merchant_key VARCHAR(255);

-- One tolerance per (user, merchant, rule): 0 is the default threshold; each "this is normal" adds a
-- step and each "this was unexpected" takes one away, within 0..3 (a cap, so real fraud still alerts).
CREATE TABLE anomaly_tolerances (
  user_id        UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  merchant_key   VARCHAR(255) NOT NULL CHECK (length(btrim(merchant_key)) > 0),
  rule           VARCHAR(40)  NOT NULL,
  level          SMALLINT     NOT NULL DEFAULT 0 CHECK (level BETWEEN 0 AND 3),
  dismissals     INTEGER      NOT NULL DEFAULT 0 CHECK (dismissals >= 0),
  confirmations  INTEGER      NOT NULL DEFAULT 0 CHECK (confirmations >= 0),
  updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, merchant_key, rule)
);
