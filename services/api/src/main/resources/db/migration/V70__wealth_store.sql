-- V70: the secure wealth store (AOS-802, D-79). Accounts → instruments → an append-only ledger.
-- Identifiers are encrypted in the app (AES-256-GCM, row-bound AAD, a key derived for wealth only) and looked up
-- by an HMAC blind index; only the last 4 characters are stored in clear. ISINs, AMFI codes, quantities and
-- amounts are not encrypted: they are needed for SQL aggregation (full-disk encryption, the private network and
-- encrypted backups protect them, D-79 / D-97 / D-98).

CREATE TABLE investment_accounts (
  id                 UUID         PRIMARY KEY,
  user_id            UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind               VARCHAR(20)  NOT NULL CHECK (kind IN ('mf_folio', 'demat', 'broker_foreign', 'equity_award', 'epf', 'nps',
                                                         'ppf', 'fd', 'crypto', 'gold_physical', 'property', 'loan_given', 'other')),
  institution        VARCHAR(120) NOT NULL CHECK (institution <> ''),
  currency           CHAR(3)      NOT NULL DEFAULT 'INR',
  identifier_enc     TEXT         NOT NULL CHECK (identifier_enc LIKE 'v2:%'),
  identifier_hmac    CHAR(64)     NOT NULL CHECK (identifier_hmac ~ '^[0-9a-f]{64}$'),
  identifier_last4   VARCHAR(4)   NOT NULL,
  owner_share        NUMERIC(5,4) NOT NULL DEFAULT 1 CHECK (owner_share > 0 AND owner_share <= 1),
  funding_account_id UUID         REFERENCES accounts(id) ON DELETE SET NULL,
  created_at         TIMESTAMPTZ  NOT NULL,
  UNIQUE (user_id, kind, identifier_hmac)
);

-- Public reference data, shared by every account that holds it.
CREATE TABLE instruments (
  id          UUID         PRIMARY KEY,
  name        VARCHAR(255) NOT NULL CHECK (name <> ''),
  asset_class VARCHAR(20)  NOT NULL CHECK (asset_class IN ('equity_in', 'equity_foreign', 'debt', 'hybrid', 'gold', 'real_estate',
                                                            'cash_like', 'crypto', 'retirement', 'other')),
  isin        VARCHAR(12)  UNIQUE CHECK (isin ~ '^[A-Z]{2}[A-Z0-9]{9}[0-9]$'),
  -- Not unique: an IDCW scheme's payout and reinvestment ISINs share one AMFI code (and one NAV).
  amfi_code   VARCHAR(10)  CHECK (amfi_code ~ '^[0-9]{1,10}$'),
  exchange    VARCHAR(10),
  ticker      VARCHAR(20),
  currency    CHAR(3)      NOT NULL DEFAULT 'INR',
  mf_plan     VARCHAR(10)  CHECK (mf_plan IN ('direct', 'regular')),
  created_at  TIMESTAMPTZ  NOT NULL,
  CHECK (isin IS NOT NULL OR amfi_code IS NOT NULL OR ticker IS NOT NULL),
  UNIQUE (exchange, ticker)
);
CREATE INDEX idx_instruments_amfi ON instruments (amfi_code) WHERE amfi_code IS NOT NULL;

-- One import (an uploaded file): counts only, never content. Undo hides its rows; nothing is deleted.
CREATE TABLE wealth_import_batches (
  id             UUID        PRIMARY KEY,
  user_id        UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reader_id      VARCHAR(40) NOT NULL,
  reader_version VARCHAR(40) NOT NULL,
  statement_from DATE,
  statement_to   DATE,
  status         VARCHAR(10) NOT NULL CHECK (status IN ('applied', 'undone')),
  events         INT         NOT NULL CHECK (events >= 0),
  added          INT         NOT NULL CHECK (added >= 0),
  duplicates     INT         NOT NULL CHECK (duplicates >= 0),
  created_at     TIMESTAMPTZ NOT NULL,
  undone_at      TIMESTAMPTZ,
  CHECK ((status = 'undone') = (undone_at IS NOT NULL))
);
CREATE INDEX idx_wealth_batches_user ON wealth_import_batches (user_id, created_at DESC);

-- The ledger. units: the signed change in holding; amount: gross, never negative (the type gives the direction).
-- Scales match the Kotlin types (Quantity 18, UnitPrice 10, FxRate 10, money 2), which refuse anything finer.
CREATE TABLE investment_transactions (
  id             UUID          PRIMARY KEY,
  user_id        UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  account_id     UUID          NOT NULL REFERENCES investment_accounts(id) ON DELETE CASCADE,
  instrument_id  UUID          NOT NULL REFERENCES instruments(id),
  batch_id       UUID          REFERENCES wealth_import_batches(id) ON DELETE CASCADE,
  txn_type       VARCHAR(20)   NOT NULL CHECK (txn_type IN ('buy', 'sip_buy', 'sell', 'switch_in', 'switch_out', 'merger_in', 'merger_out',
                                                          'dividend_payout', 'dividend_reinvest', 'split', 'bonus', 'segregation',
                                                          'transfer_in', 'transfer_out', 'reversal', 'stamp_duty', 'stt', 'tds', 'misc')),
  trade_date     DATE          NOT NULL,
  units          NUMERIC(38,18),
  unit_price     NUMERIC(30,10) CHECK (unit_price > 0),
  amount         NUMERIC(18,2)  CHECK (amount >= 0),
  currency       CHAR(3)       NOT NULL DEFAULT 'INR',
  fx_rate        NUMERIC(20,10) CHECK (fx_rate > 0),
  fx_rate_type   VARCHAR(10)   CHECK (fx_rate_type IN ('fbil', 'sbi_ttbr', 'ecb', 'other')),
  fx_rate_date   DATE,
  inr_amount     NUMERIC(18,2)  CHECK (inr_amount >= 0),
  source_ref     CHAR(64)      NOT NULL CHECK (source_ref ~ '^[0-9a-f]{64}$'),
  supersedes_id  UUID          REFERENCES investment_transactions(id),
  created_at     TIMESTAMPTZ   NOT NULL,
  CHECK ((fx_rate IS NULL) = (fx_rate_type IS NULL) AND (fx_rate IS NULL) = (fx_rate_date IS NULL)),
  UNIQUE (batch_id, source_ref)
);
CREATE INDEX idx_inv_txn_user_date ON investment_transactions (user_id, trade_date);
CREATE INDEX idx_inv_txn_user_ref ON investment_transactions (user_id, source_ref);

-- Append-only, enforced by the database too: a ledger row is never changed in place (a correction is a new row
-- that supersedes it). Deletes stay possible only through ON DELETE CASCADE (a user or account removed).
CREATE FUNCTION investment_transactions_append_only() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'investment_transactions is append-only: write a correcting row instead';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_investment_transactions_append_only
  BEFORE UPDATE ON investment_transactions
  FOR EACH ROW EXECUTE FUNCTION investment_transactions_append_only();
