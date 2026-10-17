-- V28: AI fallback categorizer cache (AOS-512, D-62). One row per merchant name (matching form), so each
-- merchant is asked once. No user, amount or transaction is stored: only the business name the model saw.
-- A row with a NULL category is a rejected reply (not asked again).
CREATE TABLE ai_category_cache (
  merchant_key VARCHAR(120) PRIMARY KEY,
  model        VARCHAR(64)  NOT NULL,
  category     VARCHAR(30),
  confidence   DOUBLE PRECISION CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
  reason       VARCHAR(200),
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CHECK ((category IS NULL) = (confidence IS NULL) AND (category IS NULL) = (reason IS NULL))
);
