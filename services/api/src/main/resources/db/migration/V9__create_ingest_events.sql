-- V9: Common intake (D-21, AOS-113). Every source posts normalized events here.
-- Append-only: kept for reprocessing; doubles as the outbox for the processing pipeline (D-27).
-- Never stores raw message text (D-33).

CREATE TYPE ingest_source AS ENUM ('sms','gmail','statement','whatsapp','manual');
CREATE TYPE ingest_event_status AS ENUM ('pending','processed','failed','ignored');
CREATE TYPE ingest_direction AS ENUM ('debit','credit');

CREATE TABLE ingest_events (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source            ingest_source NOT NULL,
  source_ref        VARCHAR(255) NOT NULL,
  occurred_at       TIMESTAMPTZ NOT NULL,
  direction         ingest_direction NOT NULL,
  amount            NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
  merchant_raw      VARCHAR(255),
  account_hint      VARCHAR(4),
  payment_method    payment_method,
  upi_vpa           VARCHAR(255),
  metadata          JSONB,
  status            ingest_event_status NOT NULL DEFAULT 'pending',
  error             TEXT,
  received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at      TIMESTAMPTZ,
  CONSTRAINT uq_ingest_event_ref UNIQUE (user_id, source, source_ref)
);

CREATE INDEX idx_ingest_events_pending ON ingest_events (received_at) WHERE status = 'pending';
CREATE INDEX idx_ingest_events_user ON ingest_events (user_id, occurred_at DESC);
