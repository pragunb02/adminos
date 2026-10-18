-- V71: what each CAS import said about each holding (AOS-805, D-79). The reconciliation status is computed from
-- these and the current ledger, so undoing an import (or importing older history) changes it without rewriting rows.
CREATE TABLE wealth_holding_checks (
  id              UUID           PRIMARY KEY,
  user_id         UUID           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  batch_id        UUID           NOT NULL REFERENCES wealth_import_batches(id) ON DELETE CASCADE,
  account_id      UUID           NOT NULL REFERENCES investment_accounts(id) ON DELETE CASCADE,
  instrument_id   UUID           REFERENCES instruments(id),
  scheme_name     VARCHAR(255)   NOT NULL CHECK (scheme_name <> ''),
  statement_from  DATE,
  statement_to    DATE           NOT NULL,
  opening_units   NUMERIC(38,18) NOT NULL,
  closing_units   NUMERIC(38,18) NOT NULL,
  valuation_date  DATE,
  valuation_nav   NUMERIC(30,10) CHECK (valuation_nav > 0),
  valuation_value NUMERIC(18,2)  CHECK (valuation_value >= 0),
  issue           VARCHAR(20)    CHECK (issue IN ('not_identified', 'unrecognised_row', 'invalid_row', 'balance_break')),
  created_at      TIMESTAMPTZ    NOT NULL,
  CHECK (instrument_id IS NOT NULL OR issue = 'not_identified')
);
CREATE INDEX idx_wealth_checks_user ON wealth_holding_checks (user_id, statement_to);
CREATE INDEX idx_wealth_checks_batch ON wealth_holding_checks (batch_id);
