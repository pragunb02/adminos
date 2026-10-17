-- V21: Reminder bookkeeping that used to live only in memory (AOS-503).
-- bills.reminder_days_sent: every "N days before" reminder already sent for the bill, e.g. '7,3,1'
--   (reminder_sent_3d/1d only covered the default days, so custom days could repeat).
-- subscriptions.renewal_reminded_for / renewal_reminder_days_sent: which renewal date the sent days
--   belong to; a new renewal date starts a fresh set.
ALTER TABLE bills ADD COLUMN reminder_days_sent TEXT;
ALTER TABLE subscriptions
  ADD COLUMN renewal_reminded_for DATE,
  ADD COLUMN renewal_reminder_days_sent TEXT;
