-- V15: Remembered category corrections (AOS-210, D-41).
-- One rule per payee: keyed by UPI ID when known, otherwise by the normalized merchant name.
CREATE TABLE category_rules (
  id           UUID PRIMARY KEY,
  user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  match_type   VARCHAR(20) NOT NULL CHECK (match_type IN ('vpa', 'merchant')),
  match_value  VARCHAR(255) NOT NULL,
  category     transaction_category NOT NULL,
  subcategory  VARCHAR(100),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, match_type, match_value)
);
