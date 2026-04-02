-- 001: Create all custom enum types
-- AdminOS MVP — 20 tables across 6 domains

-- Identity domain
CREATE TYPE onboarding_status AS ENUM ('started','gmail_connected','sms_granted','first_sync_done','completed');
CREATE TYPE user_role AS ENUM ('owner','admin','readonly');
CREATE TYPE device_type AS ENUM ('android','ios','web');

-- Ingestion domain
CREATE TYPE source_type AS ENUM ('gmail','sms','pdf','account_aggregator');
CREATE TYPE connection_status AS ENUM ('connected','disconnected','error','pending');
CREATE TYPE sync_status AS ENUM ('success','failed','partial');
CREATE TYPE sync_type AS ENUM ('historical','incremental','manual','cron_fallback');
CREATE TYPE sync_session_status AS ENUM ('queued','in_progress','completed','failed','partial');
CREATE TYPE job_type AS ENUM ('gmail_ingest','sms_process','pdf_parse','agent_briefing','subscription_detect','anomaly_check','waste_score','categorize_fallback','cancellation_draft');
CREATE TYPE job_status AS ENUM ('queued','processing','completed','failed','retrying');
CREATE TYPE fingerprint_entity_type AS ENUM ('transaction','subscription','bill','document');

-- Financial domain
CREATE TYPE transaction_type AS ENUM ('debit','credit','transfer','refund','reversal');
CREATE TYPE payment_method AS ENUM ('upi','card_debit','card_credit','netbanking','cash','wallet');
CREATE TYPE transaction_category AS ENUM ('food','transport','shopping','utilities','emi','subscription','entertainment','health','education','transfer','other');
CREATE TYPE transaction_status AS ENUM ('completed','pending','failed','reversed');
CREATE TYPE account_type AS ENUM ('savings','current','credit_card','wallet','upi');
CREATE TYPE subscription_category AS ENUM ('entertainment','fitness','productivity','cloud','food','news','gaming','other');
CREATE TYPE billing_cycle AS ENUM ('daily','weekly','monthly','quarterly','yearly');
CREATE TYPE subscription_status AS ENUM ('active','cancelled','paused','trial','unknown');
CREATE TYPE usage_status AS ENUM ('active','unused','unknown');
CREATE TYPE bill_type AS ENUM ('credit_card','electricity','internet','mobile','rent','insurance','emi','water','gas','other');
CREATE TYPE bill_status AS ENUM ('upcoming','due','overdue','paid','cancelled');

-- Agent domain
CREATE TYPE briefing_type AS ENUM ('weekly','monthly','adhoc');
CREATE TYPE briefing_status AS ENUM ('generated','delivered','opened','failed');
CREATE TYPE insight_type AS ENUM ('subscription_waste','spending_spike','bill_due','price_increase','unusual_merchant','savings_opportunity','income_received','goal_progress');
CREATE TYPE severity AS ENUM ('info','warning','critical');
CREATE TYPE insight_action_type AS ENUM ('cancel_sub','pay_bill','review_transaction','none');
CREATE TYPE insight_status AS ENUM ('pending','seen','acted','dismissed');
CREATE TYPE anomaly_type AS ENUM ('unusual_merchant','unusual_amount','unusual_time','foreign_charge','duplicate_charge','card_testing','large_debit');
CREATE TYPE anomaly_status AS ENUM ('open','confirmed_safe','confirmed_fraud','dismissed');
CREATE TYPE anomaly_resolver AS ENUM ('user','system','auto');

-- Notifications domain
CREATE TYPE reminder_type AS ENUM ('bill_due','subscription_renewal','document_expiry','custom');
CREATE TYPE recurrence AS ENUM ('once','daily','weekly','monthly');
CREATE TYPE reminder_status AS ENUM ('scheduled','sent','failed','cancelled');
CREATE TYPE notification_channel AS ENUM ('push','email','in_app');
CREATE TYPE notification_type AS ENUM ('briefing','anomaly','bill_due','subscription_flagged','sync_complete','nudge','system');
CREATE TYPE notification_status AS ENUM ('pending','sent','delivered','failed','clicked','dismissed');
CREATE TYPE weekday AS ENUM ('mon','tue','wed','thu','fri','sat','sun');

-- Audit domain
CREATE TYPE actor_type AS ENUM ('user','system','agent','worker');
