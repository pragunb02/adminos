-- V43 (tentative number; the integrator renumbers): goals on habit lenses (AOS-907).
-- One goal per (user, lens). A cut goal stores its frozen baseline (spend per period over the 3 months before it began).
CREATE TABLE coach_goals (
  id          UUID          PRIMARY KEY,
  user_id     UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  lens_id     VARCHAR(40)   NOT NULL,
  kind        VARCHAR(15)   NOT NULL CHECK (kind IN ('cut_percent', 'cap_amount')),
  value       NUMERIC(12,2) NOT NULL CHECK (value > 0),
  period      VARCHAR(10)   NOT NULL CHECK (period IN ('weekly', 'monthly')),
  baseline    NUMERIC(12,2) CHECK (baseline IS NULL OR baseline > 0),
  target      NUMERIC(12,2) NOT NULL CHECK (target >= 0),
  started_on  DATE          NOT NULL,
  created_at  TIMESTAMPTZ   NOT NULL,
  UNIQUE (user_id, lens_id),
  CHECK ((kind = 'cut_percent') = (baseline IS NOT NULL))
);
