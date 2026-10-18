-- AI message-reading fallback (AOS-705, D-106; V48).
-- A transaction a model read from a bank message the regex parsers didn't recognise, waiting in Review.
-- Not an intake event and not in any total until the user confirms it. Grounded, structured fields only:
-- no message text, ever (D-33). Dismissed rows are kept so a resent message isn't read again.
CREATE TABLE message_proposals (
  id              UUID PRIMARY KEY,
  user_id         UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  channel         VARCHAR(10)   NOT NULL CHECK (channel IN ('sms', 'whatsapp', 'gmail')),
  source_ref      VARCHAR(255)  NOT NULL CHECK (length(source_ref) > 0),
  occurred_at     TIMESTAMPTZ   NOT NULL,
  direction       VARCHAR(6)    NOT NULL CHECK (direction IN ('debit', 'credit')),
  amount          NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  account_last4   CHAR(4)       CHECK (account_last4 ~ '^[0-9]{4}$'),
  merchant        VARCHAR(60)   CHECK (length(merchant) >= 2),
  bank_code       VARCHAR(20),
  model           VARCHAR(100)  NOT NULL CHECK (model ~ '^[a-z0-9][a-z0-9._:/-]{0,99}$'),
  prompt_version  VARCHAR(100)  NOT NULL,
  status          VARCHAR(10)   NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'confirmed', 'dismissed')),
  created_at      TIMESTAMPTZ   NOT NULL,
  decided_at      TIMESTAMPTZ,
  -- The intake event a confirmation created; no foreign key, because a confirmation of a message already in the
  -- intake (a duplicate) names the event it offered, which was not stored.
  ingest_event_id UUID,
  CHECK ((status = 'pending') = (decided_at IS NULL)),
  CHECK (ingest_event_id IS NULL OR status = 'confirmed'),
  UNIQUE (user_id, channel, source_ref)
);
CREATE INDEX message_proposals_pending ON message_proposals (user_id, occurred_at DESC) WHERE status = 'pending';
