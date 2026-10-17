-- V24: Subscription waste score reasons (AOS-509, D-58).
-- waste_reasons: why waste_score is what it is, strongest first, as a JSON array of
--   {"factor": "price_increased", "points": 30, "reason": "Price went up 12.5% at the last charge"}.
-- waste_factors_dismissed: the factor codes the user had seen when they last kept the subscription
--   (comma-separated, e.g. 'price_increased,duplicate_service'); only a factor outside this set flags it again.
ALTER TABLE subscriptions
  ADD COLUMN waste_reasons TEXT,
  ADD COLUMN waste_factors_dismissed TEXT;
