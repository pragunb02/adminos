-- V73: bank-feed money to and from investments is its own nature (AOS-815, D-79): a kind of transfer, so a SIP debit
-- is neither spending nor income (D-39, D-46) and the savings rate counts it as saved (AOS-908).
ALTER TYPE transaction_nature ADD VALUE IF NOT EXISTS 'investment';
