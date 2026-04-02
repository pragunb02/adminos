-- 004: Financial domain — transactions (hypertable), accounts, subscriptions, bills

CREATE TABLE accounts (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  account_type      account_type NOT NULL,
  bank_name         VARCHAR(100),
  bank_code         VARCHAR(20),
  account_last4     VARCHAR(4) NOT NULL,
  account_name      VARCHAR(255),
  is_primary        BOOLEAN NOT NULL DEFAULT false,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  connection_id     UUID REFERENCES user_connections(id) ON DELETE SET NULL,
  metadata          JSONB,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE accounts ADD CONSTRAINT uq_acct UNIQUE (user_id, bank_code, account_last4);
CREATE INDEX idx_acct_user ON accounts (user_id);

CREATE TABLE transactions (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source_type       source_type NOT NULL,
  source_ref        VARCHAR(255),
  type              transaction_type NOT NULL,
  amount            NUMERIC(12,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  merchant_name     VARCHAR(255),
  merchant_raw      TEXT,
  merchant_category VARCHAR(10),
  account_id        UUID REFERENCES accounts(id) ON DELETE SET NULL,
  account_last4     VARCHAR(4),
  payment_method    payment_method,
  upi_vpa           VARCHAR(255),
  category          transaction_category NOT NULL DEFAULT 'other',
  subcategory       VARCHAR(100),
  category_source   VARCHAR(20) DEFAULT 'rules',
  is_recurring      BOOLEAN NOT NULL DEFAULT false,
  recurring_group_id UUID,
  status            transaction_status NOT NULL DEFAULT 'completed',
  is_anomaly        BOOLEAN NOT NULL DEFAULT false,
  anomaly_id        UUID,
  is_verified       BOOLEAN NOT NULL DEFAULT true,
  raw_email_id      VARCHAR(255),
  metadata          JSONB,
  transacted_at     TIMESTAMPTZ NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- TimescaleDB hypertable
SELECT create_hypertable('transactions', 'transacted_at');

ALTER TABLE transactions ADD CONSTRAINT chk_txn_amount CHECK (amount > 0);
CREATE INDEX idx_txn_user_time ON transactions (user_id, transacted_at DESC);
CREATE INDEX idx_txn_user_cat ON transactions (user_id, category);
CREATE INDEX idx_txn_user_merchant ON transactions (user_id, merchant_name);
CREATE INDEX idx_txn_recurring ON transactions (user_id) WHERE is_recurring = true;
CREATE INDEX idx_txn_anomaly ON transactions (user_id) WHERE is_anomaly = true;

CREATE TABLE subscriptions (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name              VARCHAR(255) NOT NULL,
  merchant_name     VARCHAR(255),
  category          subscription_category NOT NULL DEFAULT 'other',
  amount            NUMERIC(10,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  billing_cycle     billing_cycle NOT NULL,
  next_billing_date DATE,
  last_billed_date  DATE,
  first_billed_date DATE,
  status            subscription_status NOT NULL DEFAULT 'active',
  detection_source  source_type NOT NULL,
  usage_status      usage_status NOT NULL DEFAULT 'unknown',
  usage_signal      TEXT,
  waste_score       NUMERIC(3,2),
  waste_score_updated_at TIMESTAMPTZ,
  price_changed     BOOLEAN NOT NULL DEFAULT false,
  price_change_pct  NUMERIC(5,2),
  is_flagged        BOOLEAN NOT NULL DEFAULT false,
  flagged_reason    TEXT,
  flagged_at        TIMESTAMPTZ,
  flag_dismissed_at TIMESTAMPTZ,
  transaction_ids   UUID[],
  cancellation_draft TEXT,
  cancellation_draft_generated_at TIMESTAMPTZ,
  metadata          JSONB,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE subscriptions ADD CONSTRAINT chk_sub_amount CHECK (amount > 0);
ALTER TABLE subscriptions ADD CONSTRAINT chk_sub_waste CHECK (waste_score IS NULL OR (waste_score >= 0.0 AND waste_score <= 1.0));
CREATE INDEX idx_sub_user ON subscriptions (user_id);
CREATE INDEX idx_sub_status ON subscriptions (user_id, status);
CREATE INDEX idx_sub_flagged ON subscriptions (user_id) WHERE is_flagged = true;

ALTER TABLE subscriptions ADD CONSTRAINT uq_sub_merchant UNIQUE (user_id, merchant_name, billing_cycle);

CREATE TABLE bills (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  bill_type         bill_type NOT NULL,
  biller_name       VARCHAR(255) NOT NULL,
  account_ref       VARCHAR(100),
  amount            NUMERIC(12,2) NOT NULL,
  minimum_due       NUMERIC(12,2),
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  due_date          DATE NOT NULL,
  billing_period_start DATE,
  billing_period_end   DATE,
  status            bill_status NOT NULL DEFAULT 'upcoming',
  paid_at           TIMESTAMPTZ,
  paid_amount       NUMERIC(12,2),
  payment_txn_id    UUID REFERENCES transactions(id) ON DELETE SET NULL,
  detection_source  source_type NOT NULL,
  source_ref        VARCHAR(255),
  reminder_sent_3d  BOOLEAN NOT NULL DEFAULT false,
  reminder_sent_1d  BOOLEAN NOT NULL DEFAULT false,
  metadata          JSONB,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE bills ADD CONSTRAINT chk_bill_amount CHECK (amount > 0);
CREATE INDEX idx_bill_user ON bills (user_id);
CREATE INDEX idx_bill_due ON bills (user_id, due_date);
CREATE INDEX idx_bill_status ON bills (user_id, status);
CREATE INDEX idx_bill_upcoming ON bills (due_date) WHERE status IN ('upcoming', 'due');
