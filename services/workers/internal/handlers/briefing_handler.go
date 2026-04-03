package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/adminos/adminos/workers/internal/jobs"
)

// BriefingPayload is the input for the weekly briefing job.
type BriefingPayload struct {
	UserID      string `json:"user_id"`
	PeriodStart string `json:"period_start"` // YYYY-MM-DD
	PeriodEnd   string `json:"period_end"`   // YYYY-MM-DD
}

// BriefingData holds aggregated financial data for the briefing period.
type BriefingData struct {
	TotalSpent          float64            `json:"total_spent"`
	TotalIncome         float64            `json:"total_income"`
	TopCategories       []CategorySpending `json:"top_categories"`
	UpcomingBills       []BillSummary      `json:"upcoming_bills"`
	FlaggedSubscriptions []SubSummary      `json:"flagged_subscriptions"`
	AnomaliesDetected   int                `json:"anomalies_detected"`
}

// CategorySpending represents spending in a single category.
type CategorySpending struct {
	Category string  `json:"category"`
	Amount   float64 `json:"amount"`
}

// BillSummary is a brief bill representation for the briefing.
type BillSummary struct {
	BillerName string  `json:"biller_name"`
	Amount     float64 `json:"amount"`
	DueDate    string  `json:"due_date"`
}

// SubSummary is a brief subscription representation for the briefing.
type SubSummary struct {
	Name       string  `json:"name"`
	Amount     float64 `json:"amount"`
	WasteScore float64 `json:"waste_score"`
}

// BriefingResult is the output of briefing generation.
type BriefingResult struct {
	UserID            string       `json:"user_id"`
	BriefingID        string       `json:"briefing_id"`
	Content           string       `json:"content"`
	Data              BriefingData `json:"data"`
	ModelUsed         string       `json:"model_used"`
	PromptVersion     string       `json:"prompt_version"`
	TokensUsed        int          `json:"tokens_used"`
	GenerationMs      int          `json:"generation_ms"`
}

// BriefingHandler generates weekly financial briefings.
// Implements the JobHandler interface.
type BriefingHandler struct{}

func NewBriefingHandler() *BriefingHandler {
	return &BriefingHandler{}
}

func (h *BriefingHandler) JobType() string    { return jobs.TypeAgentBriefing }
func (h *BriefingHandler) TriggerType() string { return jobs.TriggerCron }

func (h *BriefingHandler) Parse(payload json.RawMessage) (any, error) {
	var p BriefingPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, fmt.Errorf("failed to parse briefing payload: %w", err)
	}
	if p.UserID == "" {
		return nil, fmt.Errorf("user_id is required")
	}
	// Default period: last 7 days
	if p.PeriodStart == "" || p.PeriodEnd == "" {
		now := time.Now()
		p.PeriodEnd = now.Format("2006-01-02")
		p.PeriodStart = now.AddDate(0, 0, -7).Format("2006-01-02")
	}
	return &p, nil
}

func (h *BriefingHandler) Execute(ctx context.Context, payload any) (any, error) {
	p := payload.(*BriefingPayload)
	start := time.Now()

	// Step 1: Query aggregated data (stub — in production, queries PostgreSQL)
	data := queryBriefingData(p.UserID, p.PeriodStart, p.PeriodEnd)

	// Step 2: Build structured prompt
	prompt := buildBriefingPrompt(p, data)

	// Step 3: Call Claude API (stub — log the prompt)
	log.Printf("[briefing] Prompt for user %s:\n%s", p.UserID, prompt)

	// In production: response, err := claudeClient.Complete(ctx, prompt)
	content := generateStubBriefing(p, data)

	elapsed := time.Since(start).Milliseconds()

	return &BriefingResult{
		UserID:        p.UserID,
		BriefingID:    fmt.Sprintf("brief_%d", time.Now().UnixMilli()),
		Content:       content,
		Data:          data,
		ModelUsed:     "claude-sonnet-stub",
		PromptVersion: "v1.0",
		TokensUsed:    0, // stub
		GenerationMs:  int(elapsed),
	}, nil
}

func (h *BriefingHandler) Persist(ctx context.Context, result any) error {
	r := result.(*BriefingResult)
	// TODO: INSERT INTO briefings with all fields
	log.Printf("[briefing] Stored briefing %s for user %s (model=%s, prompt=%s, tokens=%d, ms=%d)",
		r.BriefingID, r.UserID, r.ModelUsed, r.PromptVersion, r.TokensUsed, r.GenerationMs)
	return nil
}

func (h *BriefingHandler) Notify(ctx context.Context, result any) error {
	r := result.(*BriefingResult)
	log.Printf("[briefing] Notification sent for briefing %s to user %s", r.BriefingID, r.UserID)
	return nil
}

// queryBriefingData aggregates financial data for the briefing period.
// In production, this queries PostgreSQL.
func queryBriefingData(userID, periodStart, periodEnd string) BriefingData {
	// Stub data — in production, these would be real DB queries
	return BriefingData{
		TotalSpent:  0,
		TotalIncome: 0,
		TopCategories: []CategorySpending{},
		UpcomingBills: []BillSummary{},
		FlaggedSubscriptions: []SubSummary{},
		AnomaliesDetected: 0,
	}
}


// buildBriefingPrompt constructs the versioned Claude API prompt.
func buildBriefingPrompt(p *BriefingPayload, data BriefingData) string {
	var cats []string
	for _, c := range data.TopCategories {
		cats = append(cats, fmt.Sprintf("%s: ₹%.2f", c.Category, c.Amount))
	}

	var bills []string
	for _, b := range data.UpcomingBills {
		bills = append(bills, fmt.Sprintf("%s: ₹%.2f due %s", b.BillerName, b.Amount, b.DueDate))
	}

	var flagged []string
	for _, s := range data.FlaggedSubscriptions {
		flagged = append(flagged, fmt.Sprintf("%s: ₹%.2f (waste score: %.2f)", s.Name, s.Amount, s.WasteScore))
	}

	return fmt.Sprintf(`You are AdminOS, a personal finance assistant for an Indian user.
Generate a weekly briefing in plain English. Be concise and actionable.

Data for the week %s to %s:
- Total spent: ₹%.2f
- Total income: ₹%.2f
- Top categories: %s
- Upcoming bills: %s
- Flagged subscriptions: %s
- Anomalies detected: %d

Output format:
1. One-paragraph summary of the week
2. What needs attention this week (bills, anomalies)
3. Subscription review (if any flagged)
4. One actionable tip`,
		p.PeriodStart, p.PeriodEnd,
		data.TotalSpent, data.TotalIncome,
		strings.Join(cats, ", "),
		strings.Join(bills, ", "),
		strings.Join(flagged, ", "),
		data.AnomaliesDetected)
}

// generateStubBriefing creates a deterministic briefing (stub for Claude API).
func generateStubBriefing(p *BriefingPayload, data BriefingData) string {
	parts := []string{
		fmt.Sprintf("Weekly briefing for %s to %s.", p.PeriodStart, p.PeriodEnd),
		fmt.Sprintf("You spent ₹%.2f and earned ₹%.2f this week.", data.TotalSpent, data.TotalIncome),
	}

	if len(data.UpcomingBills) > 0 {
		parts = append(parts, fmt.Sprintf("You have %d upcoming bills to pay.", len(data.UpcomingBills)))
	}

	if len(data.FlaggedSubscriptions) > 0 {
		parts = append(parts, fmt.Sprintf("%d subscriptions look unused — consider reviewing them.", len(data.FlaggedSubscriptions)))
	}

	if data.AnomaliesDetected > 0 {
		parts = append(parts, fmt.Sprintf("%d unusual transactions were detected.", data.AnomaliesDetected))
	}

	return strings.Join(parts, " ")
}
