-- V22: Payment mandates (AOS-502): UPI AutoPay, e-NACH and card standing instructions, learnt from bank SMS.
-- No message text is stored (D-33); last_message_ref is the SMS fingerprint, so a resent message changes nothing.
CREATE TABLE mandates (
  id                UUID PRIMARY KEY,
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  rail              VARCHAR(16) NOT NULL CHECK (rail IN ('upi_autopay', 'nach', 'card_si', 'unknown')),
  payee             VARCHAR(100) NOT NULL,
  umn               VARCHAR(100),
  amount            NUMERIC(12,2) CHECK (amount > 0),
  frequency         VARCHAR(16) NOT NULL,
  status            VARCHAR(16) NOT NULL CHECK (status IN ('active', 'paused', 'revoked', 'expired', 'completed')),
  status_changed_at TIMESTAMPTZ NOT NULL,
  created_on        DATE,
  valid_until       DATE,
  next_debit_on     DATE,
  next_debit_amount NUMERIC(12,2),
  last_debit_at     TIMESTAMPTZ,
  last_debit_amount NUMERIC(12,2),
  last_debit_txn_id UUID REFERENCES transactions(id) ON DELETE SET NULL,
  bank              VARCHAR(20),
  last_message_ref  VARCHAR(64),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mandates_user ON mandates (user_id);
-- The UMN/UMRN identifies a mandate: one row per (user, UMN).
CREATE UNIQUE INDEX uq_mandates_user_umn ON mandates (user_id, lower(umn)) WHERE umn IS NOT NULL;
