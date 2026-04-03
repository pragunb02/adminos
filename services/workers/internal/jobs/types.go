package jobs

// Job type constants matching the PostgreSQL job_type enum
const (
	TypeGmailIngest       = "gmail_ingest"
	TypeSmsProcess        = "sms_process"
	TypePdfParse          = "pdf_parse"
	TypeAgentBriefing     = "agent_briefing"
	TypeSubscriptionDetect = "subscription_detect"
	TypeAnomalyCheck      = "anomaly_check"
	TypeWasteScore        = "waste_score"
	TypeCategorizeFallback = "categorize_fallback"
	TypeCancellationDraft  = "cancellation_draft"
	TypeAnomalyExplain    = "anomaly_explain"
)

// Trigger types for the workflow abstraction
const (
	TriggerCron       = "cron"
	TriggerEvent      = "event"
	TriggerUserAction = "user_action"
)
