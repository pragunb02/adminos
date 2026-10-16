-- V11: Ingest pipeline (AOS-201, D-34). Pending events are claimed, processed into transactions,
-- and linked exactly once.

-- A crashed processor's claims are retried after a timeout.
ALTER TABLE ingest_events ADD COLUMN claimed_at TIMESTAMPTZ;
CREATE INDEX idx_ingest_events_processing ON ingest_events (claimed_at) WHERE status = 'processing';

-- Exactly-once: an ingest event produces at most one transaction.
ALTER TABLE transactions ADD COLUMN ingest_event_id UUID REFERENCES ingest_events(id) ON DELETE SET NULL;
ALTER TABLE transactions ADD CONSTRAINT uq_txn_ingest_event UNIQUE (ingest_event_id);
