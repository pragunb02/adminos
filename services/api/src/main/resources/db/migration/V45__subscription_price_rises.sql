-- AOS-715 (D-74): subscription price rises, one row per (subscription, new price in whole units).
-- The detector records a rise; the price-rise alert sends it and sets notified_at. The unique key makes
-- "tell the user once per new price" a database fact: re-runs and concurrent runs can't send it twice.
CREATE TABLE subscription_price_rises (
  id               UUID PRIMARY KEY,
  user_id          UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  subscription_id  UUID          NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
  old_amount       NUMERIC(14,2) NOT NULL CHECK (old_amount > 0),
  new_amount       NUMERIC(14,2) NOT NULL CHECK (new_amount > old_amount),
  price_key        BIGINT        NOT NULL,
  currency         VARCHAR(3)    NOT NULL,
  charged_on       DATE          NOT NULL,
  transaction_id   UUID          NOT NULL,
  notified_at      TIMESTAMPTZ,
  created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
  UNIQUE (subscription_id, price_key)
);
CREATE INDEX idx_price_rises_unnotified ON subscription_price_rises (user_id, created_at) WHERE notified_at IS NULL;
