package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"time"

	"github.com/adminos/adminos/workers/internal/jobs"
	"github.com/adminos/adminos/workers/internal/workflow"
)

// WasteScoringPayload is the input for the waste scoring job.
type WasteScoringPayload struct {
	UserID         string                    `json:"user_id"`
	Subscriptions  []workflow.SubscriptionData `json:"subscriptions"`
	GmailSignalsMap map[string]workflow.GmailSignals `json:"gmail_signals_map"` // keyed by subscription ID
}

// WasteScoreResult holds the scoring result for a single subscription.
type WasteScoreResult struct {
	SubscriptionID string  `json:"subscription_id"`
	WasteScore     float64 `json:"waste_score"`
	UsageStatus    string  `json:"usage_status"`
	UsageSignal    string  `json:"usage_signal"`
	IsFlagged      bool    `json:"is_flagged"`
	FlaggedReason  string  `json:"flagged_reason,omitempty"`
	Skipped        bool    `json:"skipped"`
	SkipReason     string  `json:"skip_reason,omitempty"`
}

// WasteScoringBatchResult is the output of waste scoring for all subscriptions.
type WasteScoringBatchResult struct {
	UserID  string             `json:"user_id"`
	Results []WasteScoreResult `json:"results"`
	Total   int                `json:"total"`
	Flagged int                `json:"flagged"`
	Skipped int                `json:"skipped"`
}

// WasteScoringHandler computes waste scores for subscriptions.
// Implements the JobHandler interface.
type WasteScoringHandler struct {
	signals []workflow.WasteSignal
}

func NewWasteScoringHandler() *WasteScoringHandler {
	return &WasteScoringHandler{
		signals: DefaultWasteSignals(),
	}
}

func (h *WasteScoringHandler) JobType() string    { return jobs.TypeWasteScore }
func (h *WasteScoringHandler) TriggerType() string { return jobs.TriggerCron }

func (h *WasteScoringHandler) Parse(payload json.RawMessage) (any, error) {
	var p WasteScoringPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, fmt.Errorf("failed to parse waste scoring payload: %w", err)
	}
	if p.UserID == "" {
		return nil, fmt.Errorf("user_id is required")
	}
	return &p, nil
}

func (h *WasteScoringHandler) Execute(ctx context.Context, payload any) (any, error) {
	p := payload.(*WasteScoringPayload)

	batch := &WasteScoringBatchResult{
		UserID:  p.UserID,
		Total:   len(p.Subscriptions),
	}

	for _, sub := range p.Subscriptions {
		select {
		case <-ctx.Done():
			return batch, ctx.Err()
		default:
		}

		signals := p.GmailSignalsMap[sub.ID]
		result := h.scoreSingle(sub, signals)
		batch.Results = append(batch.Results, result)

		if result.Skipped {
			batch.Skipped++
		} else if result.IsFlagged {
			batch.Flagged++
		}
	}

	return batch, nil
}

func (h *WasteScoringHandler) scoreSingle(sub workflow.SubscriptionData, signals workflow.GmailSignals) WasteScoreResult {
	// Check 90-day dismissal cooldown (stub — in production, check flag_dismissed_at from DB)

	// Use the canonical ComputeWasteScore algorithm (same one validated by property tests)
	wasteScore := ComputeWasteScore(sub, signals)

	usageStatus := "unknown"
	if wasteScore > 0.7 {
		usageStatus = "unused"
	} else if wasteScore < 0.3 {
		usageStatus = "active"
	}

	usageSignal := DescribeSignals(sub, signals)

	result := WasteScoreResult{
		SubscriptionID: sub.ID,
		WasteScore:     wasteScore,
		UsageStatus:    usageStatus,
		UsageSignal:    usageSignal,
		IsFlagged:      wasteScore > 0.7,
	}

	if result.IsFlagged {
		result.FlaggedReason = fmt.Sprintf("Waste score %.2f exceeds threshold. %s", wasteScore, usageSignal)
	}

	return result
}

func (h *WasteScoringHandler) Persist(ctx context.Context, result any) error {
	r := result.(*WasteScoringBatchResult)
	// TODO: UPDATE subscriptions SET waste_score, usage_status, is_flagged, etc.
	log.Printf("[waste_score] Scored %d subscriptions for user %s: %d flagged, %d skipped",
		r.Total, r.UserID, r.Flagged, r.Skipped)
	return nil
}

func (h *WasteScoringHandler) Notify(ctx context.Context, result any) error {
	r := result.(*WasteScoringBatchResult)
	if r.Flagged > 0 {
		log.Printf("[waste_score] Notification: %d subscriptions flagged for user %s", r.Flagged, r.UserID)
	}
	return nil
}


// ComputeWasteScore calculates the waste score (0.0-1.0) for a subscription.
// Uses 4 signals: months paid, login emails, usage emails, transaction consistency.
func ComputeWasteScore(sub workflow.SubscriptionData, signals workflow.GmailSignals) float64 {
	score := 0.0

	monthsPaid := monthsBetween(sub.FirstBilledDate, time.Now())

	// Signal 1 + 2: No engagement signals from Gmail
	if signals.LoginEmailCount == 0 && signals.UsageEmailCount == 0 {
		score += 0.4 // no engagement signals at all
	}

	// Signal 2: Paying for 3+ months with no login emails
	if monthsPaid >= 3 && signals.LoginEmailCount == 0 {
		score += 0.3
	}

	// Signal 1: Long-running subscription (6+ months)
	if monthsPaid >= 6 {
		score += 0.1
	}

	// Signal 3 + 4: Still being charged but no usage emails
	if monthsPaid > 0 && signals.UsageEmailCount == 0 {
		score += 0.2
	}

	return math.Min(score, 1.0)
}

// DescribeSignals generates a human-readable description of the waste signals.
func DescribeSignals(sub workflow.SubscriptionData, signals workflow.GmailSignals) string {
	monthsPaid := monthsBetween(sub.FirstBilledDate, time.Now())
	return fmt.Sprintf("months_paid=%d, login_emails=%d, usage_emails=%d",
		monthsPaid, signals.LoginEmailCount, signals.UsageEmailCount)
}

// monthsBetween calculates the number of months between two times.
func monthsBetween(start, end time.Time) int {
	if end.Before(start) {
		return 0
	}
	years := end.Year() - start.Year()
	months := int(end.Month()) - int(start.Month())
	total := years*12 + months
	if total < 0 {
		return 0
	}
	return total
}

// --- Default Waste Signals (Strategy pattern implementations) ---

// MonthsPaidSignal evaluates how long the subscription has been paid.
type MonthsPaidSignal struct{}

func (s *MonthsPaidSignal) Name() string    { return "months_paid" }
func (s *MonthsPaidSignal) Weight() float64 { return 0.3 }
func (s *MonthsPaidSignal) Evaluate(sub workflow.SubscriptionData, signals workflow.GmailSignals) float64 {
	months := monthsBetween(sub.FirstBilledDate, time.Now())
	if months >= 6 {
		return 1.0
	}
	if months >= 3 {
		return 0.6
	}
	return 0.2
}

// LoginEmailSignal evaluates login/welcome-back email presence.
type LoginEmailSignal struct{}

func (s *LoginEmailSignal) Name() string    { return "login_emails" }
func (s *LoginEmailSignal) Weight() float64 { return 0.3 }
func (s *LoginEmailSignal) Evaluate(sub workflow.SubscriptionData, signals workflow.GmailSignals) float64 {
	if signals.LoginEmailCount == 0 {
		return 1.0 // no login emails = high waste signal
	}
	if signals.LoginEmailCount <= 2 {
		return 0.5
	}
	return 0.0
}

// UsageEmailSignal evaluates receipt/usage email presence.
type UsageEmailSignal struct{}

func (s *UsageEmailSignal) Name() string    { return "usage_emails" }
func (s *UsageEmailSignal) Weight() float64 { return 0.2 }
func (s *UsageEmailSignal) Evaluate(sub workflow.SubscriptionData, signals workflow.GmailSignals) float64 {
	if signals.UsageEmailCount == 0 {
		return 1.0
	}
	if signals.UsageEmailCount <= 3 {
		return 0.4
	}
	return 0.0
}

// TransactionConsistencySignal evaluates recent charge consistency.
type TransactionConsistencySignal struct{}

func (s *TransactionConsistencySignal) Name() string    { return "transaction_consistency" }
func (s *TransactionConsistencySignal) Weight() float64 { return 0.2 }
func (s *TransactionConsistencySignal) Evaluate(sub workflow.SubscriptionData, signals workflow.GmailSignals) float64 {
	// If subscription is active but no usage, that's a waste signal
	if sub.Status == "active" && signals.UsageEmailCount == 0 && signals.LoginEmailCount == 0 {
		return 0.8
	}
	return 0.0
}

// DefaultWasteSignals returns the standard set of waste signal evaluators.
func DefaultWasteSignals() []workflow.WasteSignal {
	return []workflow.WasteSignal{
		&MonthsPaidSignal{},
		&LoginEmailSignal{},
		&UsageEmailSignal{},
		&TransactionConsistencySignal{},
	}
}
