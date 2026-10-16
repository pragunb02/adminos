-- V14: What a transaction *is*, independent of direction (AOS-208/209, D-39).
-- The console adds up by nature: transfers and reversals are neither spending nor income;
-- refunds reduce spending instead of counting as income.
CREATE TYPE transaction_nature AS ENUM ('spend', 'income', 'transfer', 'refund', 'reversal');

ALTER TABLE transactions ADD COLUMN nature transaction_nature;
UPDATE transactions SET nature = CASE type
  WHEN 'credit' THEN 'income'::transaction_nature
  WHEN 'transfer' THEN 'transfer'::transaction_nature
  WHEN 'refund' THEN 'refund'::transaction_nature
  WHEN 'reversal' THEN 'reversal'::transaction_nature
  ELSE 'spend'::transaction_nature END;
ALTER TABLE transactions ALTER COLUMN nature SET NOT NULL;
ALTER TABLE transactions ALTER COLUMN nature SET DEFAULT 'spend';
CREATE INDEX idx_txn_user_nature_time ON transactions (user_id, nature, transacted_at);

-- Links carry a relation (what kind of pair) separately from their state (merged/suggested/rejected).
ALTER TABLE transaction_links ADD COLUMN relation VARCHAR(20) NOT NULL DEFAULT 'duplicate';
CREATE INDEX idx_txn_links_user_relation_kind ON transaction_links (user_id, relation, kind);
