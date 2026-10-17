-- V50 (AOS-903, D-88): order receipts from quick-commerce, food-delivery and e-commerce emails.
-- Written by the Gmail worker (one row per order, every email about it merged in), matched to
-- the payment by the API. Structured fields only (D-33): no body, no item names, no address; the
-- order number is kept only as a SHA-256 hash.
-- Tentative number: the integrator renumbers at merge.

CREATE TABLE order_receipts (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  app             VARCHAR(40) NOT NULL,
  vertical        VARCHAR(20) NOT NULL CHECK (vertical IN ('quick_commerce', 'food_delivery', 'ecommerce')),
  order_ref_hash  CHAR(64) NOT NULL,
  source_ref      VARCHAR(255) NOT NULL,
  ordered_at      TIMESTAMPTZ NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'placed' CHECK (status IN ('placed', 'cancelled')),
  order_total     NUMERIC(12,2) CHECK (order_total > 0),
  item_count      INT CHECK (item_count > 0),
  payment_mode    VARCHAR(16) NOT NULL DEFAULT 'unknown'
                  CHECK (payment_mode IN ('upi', 'card', 'netbanking', 'wallet', 'pay_later', 'cod', 'split', 'unknown')),
  paid_amount     NUMERIC(12,2) CHECK (paid_amount > 0),
  -- Matching to the payment (API): unmatched → matched / suggested / no_payment (wallet, pay-later, cash).
  match_state     VARCHAR(16) NOT NULL DEFAULT 'unmatched'
                  CHECK (match_state IN ('unmatched', 'matched', 'suggested', 'no_payment')),
  transaction_id  UUID REFERENCES transactions(id) ON DELETE SET NULL,
  match_score     INT,
  match_reason    VARCHAR(200),
  matched_by      VARCHAR(16) CHECK (matched_by IN ('rules', 'user')),
  -- Payments the user said are not this order's (comma-separated UUIDs, D-10): never proposed again.
  rejected_transaction_ids TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- A deleted payment leaves transaction_id NULL; the API then reads the receipt as unmatched again.
  CONSTRAINT uq_order_receipt UNIQUE (user_id, app, order_ref_hash)
);

-- One receipt per payment (1:1): a payment can't be two orders.
CREATE UNIQUE INDEX uq_order_receipt_payment ON order_receipts (transaction_id) WHERE match_state = 'matched';
CREATE INDEX idx_order_receipts_user_time ON order_receipts (user_id, ordered_at);
CREATE INDEX idx_order_receipts_unmatched ON order_receipts (ordered_at) WHERE match_state = 'unmatched';

-- Fees on top of the items, one row per kind (the first email that names a kind wins).
CREATE TABLE order_receipt_fees (
  receipt_id  UUID NOT NULL REFERENCES order_receipts(id) ON DELETE CASCADE,
  kind        VARCHAR(16) NOT NULL
              CHECK (kind IN ('delivery', 'handling', 'small_cart', 'platform', 'surge', 'packaging', 'tip')),
  amount      NUMERIC(10,2) NOT NULL CHECK (amount > 0),
  PRIMARY KEY (receipt_id, kind)
);

-- Refunds announced for an order. The same amount twice ("initiated", then "credited") is one refund.
CREATE TABLE order_receipt_refunds (
  receipt_id   UUID NOT NULL REFERENCES order_receipts(id) ON DELETE CASCADE,
  amount       NUMERIC(12,2) NOT NULL CHECK (amount > 0),
  source_ref   VARCHAR(255) NOT NULL,
  refunded_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (receipt_id, amount)
);
