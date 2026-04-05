package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adminos/adminos/workers/internal/claude"
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
	TotalSpent           float64            `json:"total_spent"`
	TotalIncome          float64            `json:"total_income"`
	TopCategories        []CategorySpending `json:"top_categories"`
	UpcomingBills        []BillSummary      `json:"upcoming_bills"`
	FlaggedSubscriptions []SubSummary       `json:"flagged_subscriptions"`
	AnomaliesDetected    int                `json:"anomalies_detected"`
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
	UserID        string       `json:"user_id"`
	BriefingID    string       `json:"briefing_id"`
	Content       string       `json:"content"`
	Data          BriefingData `json:"data"`
	PeriodStart   string       `json:"period_start"`
	PeriodEnd     string       `json:"period_end"`
	ModelUsed     string       `json:"model_used"`
	PromptVersion string       `json:"prompt_version"`
	TokensUsed    int          `json:"tokens_used"`
	GenerationMs  int          `json:"generation_ms"`
}

// BriefingHandler generates weekly financial briefings.
// Implements the JobHandler interface.
type BriefingHandler struct {
	db           *pgxpool.Pool
	claudeClient *claude.Client
}

func NewBriefingHandler(db *pgxpool.Pool, claudeClient *claude.Client) *BriefingHandler {
	return &BriefingHandler{db: db, claudeClient: claudeClient}
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

	data := queryBriefingData(p.UserID, p.PeriodStart, p.PeriodEnd)
	prompt := buildBriefingPrompt(p, data)

	var content string
	var modelUsed string
	var promptVersion string
	var tokensUsed int

	if h.claudeClient != nil {
		result, err := h.claudeClient.Complete(ctx, claude.BriefingSystemPrompt, prompt, 1024)
		if err != nil {
			if _, ok := err.(*claude.PermanentError); ok {
				log.Printf("[briefing] Claude permanent error, using stub: %v", err)
				content = generateStubBriefing(p, data)
				modelUsed = "stub"
				promptVersion = claude.PromptVersionBriefing
			} else {
				return nil, fmt.Errorf("claude API call failed: %w", err)
			}
		} else {
			content = result.Text
			modelUsed = result.Model
			promptVersion = claude.PromptVersionBriefing
			tokensUsed = result.InputTokens + result.OutputTokens
		}
	} else {
		content = generateStubBriefing(p, data)
		modelUsed = "stub"
		promptVersion = claude.PromptVersionBriefing
	}

	elapsed := time.Since(start).Milliseconds()

	return &BriefingResult{
		UserID:        p.UserID,
		BriefingID:    fmt.Sprintf("brief_%d", time.Now().UnixMilli()),
		Content:       content,
		Data:          data,
		PeriodStart:   p.PeriodStart,
		PeriodEnd:     p.PeriodEnd,
		ModelUsed:     modelUsed,
		PromptVersion: promptVersion,
		TokensUsed:    tokensUsed,
		GenerationMs:  int(elapsed),
	}, nil
}

func (h *BriefingHandler) Persist(ctx context.Context, result any) error {
	r := result.(*BriefingResult)

	if h.db == nil {
		log.Printf("[briefing] Stored briefing %s for user %s (model=%s, prompt=%s, tokens=%d, ms=%d)",
			r.BriefingID, r.UserID, r.ModelUsed, r.PromptVersion, r.TokensUsed, r.GenerationMs)
		return nil
	}

	periodStart, err := time.Parse("2006-01-02", r.PeriodStart)
	if err != nil {
		return fmt.Errorf("parse period_start: %w", err)
	}
	periodEnd, err := time.Parse("2006-01-02", r.PeriodEnd)
	if err != nil {
		return fmt.Errorf("parse period_end: %w", err)
	}

	_, err = h.db.Exec(ctx, `
		INSERT INTO briefings (user_id, period_start, period_end, type, content,
			total_spent, total_income, subscriptions_flagged, anomalies_detected,
			bills_upcoming, model_used, prompt_version, tokens_used, generation_ms,
			created_at, updated_at)
		VALUES ($1, $2, $3, 'weekly', $4,
			$5, $6, $7, $8, $9, $10, $11, $12, $13, now(), now())`,
		r.UserID, periodStart, periodEnd, r.Content,
		r.Data.TotalSpent, r.Data.TotalIncome,
		len(r.Data.FlaggedSubscriptions), r.Data.AnomaliesDetected,
		len(r.Data.UpcomingBills),
		r.ModelUsed, r.PromptVersion, r.TokensUsed, r.GenerationMs)
	if err != nil {
		return fmt.Errorf("insert briefing: %w", err)
	}

	return nil
}

func (h *BriefingHandler) Notify(ctx context.Context, result any) error {
	r := result.(*BriefingResult)
	log.Printf("[briefing] Notification sent for briefing %s to user %s", r.BriefingID, r.UserID)
	return nil
}

// queryBriefingData aggregates financial data for the briefing period.
func queryBriefingData(userID, periodStart, periodEnd string) BriefingData {
	return BriefingData{
		TotalSpent:           0,
		TotalIncome:          0,
		TopCategories:        []CategorySpending{},
		UpcomingBills:        []BillSummary{},
		FlaggedSubscriptions: []SubSummary{},
		AnomaliesDetected:    0,
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
