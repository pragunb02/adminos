-- V31: the owner's habit-lens overrides (AOS-901, D-80, D-81).
-- One row per (user, lens, merchant): include = true adds the merchant to the lens, false takes it out.
-- merchant_key is the merchant name trimmed and lower-cased; merchant keeps the name as shown.
-- Lens ids come from resources/coach/lenses.json (data, not a table), so there is no foreign key for them.
CREATE TABLE lens_overrides (
  user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  lens_id      VARCHAR(40)  NOT NULL CHECK (lens_id ~ '^[a-z][a-z0-9_]{1,39}$'),
  merchant_key VARCHAR(255) NOT NULL CHECK (merchant_key <> '' AND merchant_key = lower(merchant_key)),
  merchant     VARCHAR(255) NOT NULL,
  include      BOOLEAN      NOT NULL,
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, lens_id, merchant_key)
);
