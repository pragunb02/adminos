-- V80: Confirmed document fields (AOS-717). Written only when the owner saves them (a confirmed proposal or a typed
-- value); the text read from the file is never stored. A document number is kept as its last 4 letters or digits only.
ALTER TABLE documents
  ADD COLUMN issuer       VARCHAR(120),
  ADD COLUMN number_last4 VARCHAR(4) CHECK (number_last4 ~ '^[A-Z0-9]{4}$'),
  ADD COLUMN amount       NUMERIC(14, 2) CHECK (amount > 0 AND amount <= 100000000);
