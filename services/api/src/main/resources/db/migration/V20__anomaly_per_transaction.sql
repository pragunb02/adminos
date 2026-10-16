-- V20: At most one anomaly per transaction (AOS-505). Events can be re-processed and several API
-- instances may check the same transaction; the database keeps the second check from adding a copy.
CREATE UNIQUE INDEX uq_anomalies_transaction ON anomalies (transaction_id);
