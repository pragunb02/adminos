-- V51 (AOS-909, D-89): pay-later (BNPL) is a payment method for purchases and a bill type for its bills.
-- Tentative number: the integrator renumbers at merge.
ALTER TYPE payment_method ADD VALUE IF NOT EXISTS 'pay_later';
ALTER TYPE bill_type ADD VALUE IF NOT EXISTS 'pay_later';
