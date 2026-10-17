-- V90 (tentative number): Splitwise import (AOS-911, proposed D-111).
-- settlement: money paid to or received from a friend to settle a shared bill; neither spending nor income.
-- (ADD VALUE runs inside Flyway's transaction on Postgres 12+; nothing in this migration uses the new value.)
ALTER TYPE transaction_nature ADD VALUE IF NOT EXISTS 'settlement';
-- splitwise: the owner's share of a bill a friend paid (no bank record behind it).
ALTER TYPE source_type ADD VALUE IF NOT EXISTS 'splitwise';

-- own_share: the owner's part of a bill they paid for friends. Totals count it instead of amount; the rest is owed
-- by friends. Null = not split. Written only by the split service, never by the general update.
ALTER TABLE transactions ADD COLUMN own_share NUMERIC(12,2);
ALTER TABLE transactions ADD CONSTRAINT chk_txn_own_share CHECK (own_share IS NULL OR (own_share >= 0 AND own_share <= amount));

-- One row per upload, so a whole import can be undone.
CREATE TABLE split_imports (
  id          UUID PRIMARY KEY,
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source      VARCHAR(20) NOT NULL CHECK (source IN ('splitwise_csv')),
  rows_read   INT NOT NULL CHECK (rows_read >= 0),
  created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_split_imports_user ON split_imports (user_id, created_at);

-- One row per Splitwise expense or payment, from the owner's side only.
-- source_ref:       SHA-256 of date, description, cost, currency, kind and occurrence: re-imports add only new rows.
-- description_enc / counterparty_enc: TokenEncryptor v2 (context split_entries:<id>:<field>). They can name
--                   friends, so they are encrypted at rest, never logged and never sent to a model.
-- owner_net:        the change to the owner's Splitwise balance (+ friends owe them, − they owe).
-- bank_amount:      the bank amount to look for (the whole bill, or the settle-up amount).
-- prior_*:          the transaction before a settle-up was applied, so undo restores it exactly.
-- rejected_ids:     transactions the owner said are not this entry (comma-separated UUIDs, D-10).
CREATE TABLE split_entries (
  id                     UUID PRIMARY KEY,
  user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  import_id              UUID NOT NULL REFERENCES split_imports(id) ON DELETE CASCADE,
  source                 VARCHAR(20) NOT NULL CHECK (source IN ('splitwise_csv')),
  source_ref             VARCHAR(64) NOT NULL,
  kind                   VARCHAR(10) NOT NULL CHECK (kind IN ('expense', 'payment')),
  effect                 VARCHAR(15) NOT NULL CHECK (effect IN ('adjust_debit', 'owed_spend', 'settle_out', 'settle_in', 'none')),
  state                  VARCHAR(12) NOT NULL CHECK (state IN ('applied', 'suggested', 'unmatched', 'ignored', 'no_effect')),
  entry_date             DATE NOT NULL,
  description_enc        TEXT NOT NULL,
  counterparty_enc       TEXT,
  splitwise_category     VARCHAR(60),
  cost                   NUMERIC(12,2) NOT NULL CHECK (cost > 0),
  currency               VARCHAR(3) NOT NULL,
  owner_net              NUMERIC(12,2) NOT NULL,
  owner_share            NUMERIC(12,2) NOT NULL CHECK (owner_share >= 0 AND owner_share <= cost),
  bank_amount            NUMERIC(12,2) NOT NULL,
  transaction_id         UUID REFERENCES transactions(id) ON DELETE SET NULL,
  created_transaction    BOOLEAN NOT NULL DEFAULT false,
  prior_nature           VARCHAR(20),
  prior_category         VARCHAR(30),
  prior_subcategory      VARCHAR(100),
  prior_category_source  VARCHAR(20),
  rejected_ids           TEXT NOT NULL DEFAULT '',
  score                  INT,
  reason                 VARCHAR(200),
  created_at             TIMESTAMPTZ NOT NULL,
  updated_at             TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_split_entries_ref UNIQUE (user_id, source_ref)
);
-- No CHECK ties applied/suggested to a transaction: deleting a transaction (ON DELETE SET NULL) must never fail.
-- An entry whose transaction is gone reads back as unmatched.
-- A transaction is split or settled by at most one entry.
CREATE UNIQUE INDEX uq_split_entries_applied_txn ON split_entries (transaction_id) WHERE state = 'applied';
CREATE INDEX idx_split_entries_user_state ON split_entries (user_id, state, entry_date);
CREATE INDEX idx_split_entries_import ON split_entries (import_id);
