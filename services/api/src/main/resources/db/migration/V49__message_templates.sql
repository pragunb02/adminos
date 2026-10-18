-- Template learning (AOS-706, D-107; V49).
-- Masked message shapes only: every value (amount, balance, last 4, date, payee, any other number) is a slot,
-- so no digit, amount, payee or message text is ever stored (D-33). The CHECKs hold that at the database too.

-- The shape of the message an AI reading came from, learnt from when the user confirms it.
ALTER TABLE message_proposals ADD COLUMN shape VARCHAR(1000) CHECK (shape !~ '[0-9]' AND position('<AMT>' in shape) > 0);

-- A learnt format: one sender's wording for one direction. Proposed after a few confirmations; read by code only
-- once the user approves it.
CREATE TABLE message_templates (
  id          UUID PRIMARY KEY,
  user_id     UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  channel     VARCHAR(10)   NOT NULL CHECK (channel IN ('sms', 'whatsapp', 'gmail')),
  bank_code   VARCHAR(20),
  direction   VARCHAR(6)    NOT NULL CHECK (direction IN ('debit', 'credit')),
  shape       VARCHAR(1000) NOT NULL CHECK (shape !~ '[0-9]' AND position('<AMT>' in shape) > 0),
  status      VARCHAR(10)   NOT NULL CHECK (status IN ('observing', 'proposed', 'approved', 'rejected')),
  seen        INT           NOT NULL CHECK (seen >= 1),
  hits        INT           NOT NULL DEFAULT 0 CHECK (hits >= 0),
  created_at  TIMESTAMPTZ   NOT NULL,
  updated_at  TIMESTAMPTZ   NOT NULL
);
CREATE INDEX message_templates_user_format ON message_templates (user_id, channel, bank_code, direction);
