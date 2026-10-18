-- V92: the latest known balance per bank account (AOS-720, D-170).
-- One row per balance an input showed: a trusted bank SMS's "Avl Bal", or a statement's reconciled closing balance.
-- Rows are facts, never edited; the latest per account is the newest observed_at. The figure is encrypted
-- (AES-GCM, TokenEncryptor, bound to the row id), so the database holds no readable balance. No message text.
CREATE TABLE balance_readings (
  id             UUID        PRIMARY KEY,
  user_id        UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  bank_code      VARCHAR(16) CHECK (bank_code ~ '^[a-z0-9_]{1,16}$'),
  account_last4  CHAR(4)     NOT NULL CHECK (account_last4 ~ '^[0-9]{4}$'),
  source         VARCHAR(16) NOT NULL CHECK (source IN ('sms', 'statement')),
  source_ref     CHAR(64)    NOT NULL CHECK (source_ref ~ '^[0-9a-f]{64}$'),
  amount         TEXT        NOT NULL CHECK (amount LIKE 'v2:%' AND length(amount) <= 256),
  observed_at    TIMESTAMPTZ NOT NULL,
  recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- The same message or statement records once.
  CONSTRAINT uq_balance_readings_source UNIQUE (user_id, source, source_ref)
);
CREATE INDEX idx_balance_readings_account ON balance_readings (user_id, account_last4, observed_at DESC);
