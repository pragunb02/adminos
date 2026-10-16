-- V13: Cross-source duplicate detection (AOS-207).

-- UPI/IMPS reference (RRN): the strongest proof that two records are the same payment.
ALTER TABLE ingest_events ADD COLUMN payment_ref VARCHAR(32);
ALTER TABLE transactions ADD COLUMN payment_ref VARCHAR(32);
CREATE INDEX idx_txn_user_payment_ref ON transactions (user_id, payment_ref) WHERE payment_ref IS NOT NULL;
CREATE INDEX idx_txn_user_type_amount_time ON transactions (user_id, type, amount, transacted_at);

-- How records from different sources relate to a transaction. Source rows stay immutable; merges are links.
--   merged:    ingest_event_id was folded into transaction_id (or related_transaction_id after a confirmed suggestion)
--   suggested: related_transaction_id may be a duplicate of transaction_id (awaiting the user)
--   rejected:  the user said they're different; never suggested again
CREATE TYPE transaction_link_kind AS ENUM ('merged', 'suggested', 'rejected');

CREATE TABLE transaction_links (
  id                     UUID PRIMARY KEY,
  user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  transaction_id         UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
  related_transaction_id UUID REFERENCES transactions(id) ON DELETE SET NULL,
  ingest_event_id        UUID REFERENCES ingest_events(id) ON DELETE SET NULL,
  source                 VARCHAR(20) NOT NULL,
  kind                   transaction_link_kind NOT NULL,
  score                  INTEGER NOT NULL,
  reason                 TEXT NOT NULL,
  rules_version          VARCHAR(20) NOT NULL,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_links_txn ON transaction_links (transaction_id);
CREATE INDEX idx_txn_links_related ON transaction_links (related_transaction_id);
CREATE INDEX idx_txn_links_user_kind ON transaction_links (user_id, kind);
CREATE UNIQUE INDEX uq_txn_links_event ON transaction_links (ingest_event_id) WHERE ingest_event_id IS NOT NULL AND kind = 'merged';
