-- V93: the owner's confirmed or corrected salary (AOS-722, D-76 follow-up). One row per user, the latest choice.
-- payer is the employer as the bank shows it (null = credits that come without a name). Like transactions.merchant_name
-- and every amount in this schema it is plain text: it is a company name, not a person's, and the forecast compares it.
-- The API validates every field first; these checks are the last line.
CREATE TABLE salary_choices (
  user_id    UUID          PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  payer      TEXT          NULL CHECK (payer IS NULL OR (char_length(payer) BETWEEN 1 AND 100 AND payer = btrim(payer))),
  pay_day    SMALLINT      NOT NULL CHECK (pay_day BETWEEN 1 AND 31),
  amount     NUMERIC(14,2) NOT NULL CHECK (amount > 0 AND amount <= 1000000000.00),
  kind       TEXT          NOT NULL CHECK (kind IN ('confirmed', 'corrected')),
  created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
