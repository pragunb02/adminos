-- V10: Enum values for the ingest pipeline (AOS-201, D-34).
-- Separate from V11 because Postgres can't use a newly added enum value in the same
-- transaction that adds it (55P04), and Flyway runs each migration in one transaction.

-- Claiming: pending → processing → processed | ignored | failed.
ALTER TYPE ingest_event_status ADD VALUE IF NOT EXISTS 'processing';

-- Every intake source can own transactions.
ALTER TYPE source_type ADD VALUE IF NOT EXISTS 'statement';
ALTER TYPE source_type ADD VALUE IF NOT EXISTS 'whatsapp';
ALTER TYPE source_type ADD VALUE IF NOT EXISTS 'manual';
