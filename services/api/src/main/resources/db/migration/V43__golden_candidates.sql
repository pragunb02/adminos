-- V43: the user's local golden set (AOS-708). Scrubbed, golden.csv-shaped copies of answers from the Review queue:
-- a cleaned business name in a synthetic narration, the payment method, the direction and the chosen category.
-- No amount, date, reference, account or card number, UPI ID or message text. Deleted with the user.
CREATE TABLE golden_candidates (
  id              UUID PRIMARY KEY,
  user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  merchant_raw    VARCHAR(120) NOT NULL CHECK (position(',' in merchant_raw) = 0),
  payment_method  VARCHAR(20),
  direction       VARCHAR(10)  NOT NULL CHECK (direction IN ('debit', 'credit')),
  category        VARCHAR(30)  NOT NULL CHECK (category <> 'transfer'),
  created_at      TIMESTAMPTZ  NOT NULL
);
-- NULLS NOT DISTINCT: two rows without a payment method are still duplicates (Postgres 15+).
CREATE UNIQUE INDEX golden_candidates_unique ON golden_candidates (user_id, merchant_raw, payment_method, direction, category) NULLS NOT DISTINCT;
CREATE INDEX golden_candidates_user ON golden_candidates (user_id, created_at);
