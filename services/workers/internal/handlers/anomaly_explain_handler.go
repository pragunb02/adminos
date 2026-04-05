package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adminos/adminos/workers/internal/claude"
	"github.com/adminos/adminos/workers/internal/jobs"
)

// AnomalyExplainPayload is the input for the anomaly explanation job.
type AnomalyExplainPayload struct {
	UserID         string          `json:"user_id"`
	AnomalyID      string          `json:"anomaly_id"`
	TransactionID  string          `json:"transaction_id"`
	Amount         float64         `json:"amount"`
	Currency       string          `json:"currency"`
	MerchantName   string          `json:"merchant_name"`
	TransactedAt   string          `json:"transacted_at"`
	TriggeredRules []TriggeredRule `json:"triggered_rules"`
}

// TriggeredRule represents a single anomaly rule that fired.
type TriggeredRule struct {
	RuleName   string  `json:"rule_name"`
	Confidence float64 `json:"confidence"`
	Reason     string  `json:"reason"`
}

// AnomalyExplainResult is the output of anomaly explanation.
type AnomalyExplainResult struct {
	AnomalyID        string `json:"anomaly_id"`
	AgentExplanation string `json:"agent_explanation"`
	Prompt           string `json:"prompt"`
	ModelUsed        string `json:"model_used"`
}

// AnomalyExplainHandler generates AI explanations for detected anomalies.
// Implements the JobHandler interface.
type AnomalyExplainHandler struct {
	db           *pgxpool.Pool
	claudeClient *claude.Client
}

func NewAnomalyExplainHandler(db *pgxpool.Pool, claudeClient *claude.Client) *AnomalyExplainHandler {
	return &AnomalyExplainHandler{db: db, claudeClient: claudeClient}
}

func (h *AnomalyExplainHandler) JobType() string    { return jobs.TypeAnomalyExplain }
func (h *AnomalyExplainHandler) TriggerType() string { return jobs.TriggerEvent }

func (h *AnomalyExplainHandler) Parse(payload json.RawMessage) (any, error) {
	var p AnomalyExplainPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, fmt.Errorf("failed to parse anomaly explain payload: %w", err)
	}
	if p.AnomalyID == "" {
		return nil, fmt.Errorf("anomaly_id is required")
	}
	if p.TransactionID == "" {
		return nil, fmt.Errorf("transaction_id is required")
	}
	return &p, nil
}

func (h *AnomalyExplainHandler) Execute(ctx context.Context, payload any) (any, error) {
	p := payload.(*AnomalyExplainPayload)

	prompt := buildAnomalyPrompt(p)
	log.Printf("[anomaly_explain] Prompt for anomaly %s:\n%s", p.AnomalyID, prompt)

	var explanation string
	var modelUsed string

	if h.claudeClient != nil {
		result, err := h.claudeClient.Complete(ctx, claude.AnomalySystemPrompt, prompt, 256)
		if err != nil {
			if _, ok := err.(*claude.PermanentError); ok {
				log.Printf("[anomaly_explain] Claude permanent error, using stub: %v", err)
				explanation = generateStubExplanation(p)
				modelUsed = "stub"
			} else {
				return nil, fmt.Errorf("claude API call failed: %w", err)
			}
		} else {
			explanation = result.Text
			modelUsed = result.Model
		}
	} else {
		explanation = generateStubExplanation(p)
		modelUsed = "stub"
	}

	return &AnomalyExplainResult{
		AnomalyID:        p.AnomalyID,
		AgentExplanation: explanation,
		Prompt:           prompt,
		ModelUsed:        modelUsed,
	}, nil
}

func (h *AnomalyExplainHandler) Persist(ctx context.Context, result any) error {
	r := result.(*AnomalyExplainResult)

	if h.db == nil {
		log.Printf("[anomaly_explain] Stored explanation for anomaly %s: %s",
			r.AnomalyID, r.AgentExplanation)
		return nil
	}

	_, err := h.db.Exec(ctx, `
		UPDATE anomalies SET agent_explanation = $2, updated_at = now()
		WHERE id = $1`,
		r.AnomalyID, r.AgentExplanation)
	if err != nil {
		return fmt.Errorf("update anomaly explanation: %w", err)
	}

	return nil
}

func (h *AnomalyExplainHandler) Notify(ctx context.Context, result any) error {
	r := result.(*AnomalyExplainResult)
	log.Printf("[anomaly_explain] Push notification sent for anomaly %s (bypasses quiet hours)",
		r.AnomalyID)
	return nil
}

// buildAnomalyPrompt constructs the Claude API prompt.
func buildAnomalyPrompt(p *AnomalyExplainPayload) string {
	var rules []string
	for _, r := range p.TriggeredRules {
		rules = append(rules, fmt.Sprintf("- %s (confidence: %.2f): %s", r.RuleName, r.Confidence, r.Reason))
	}

	return fmt.Sprintf(`Transaction: ₹%.2f at %s on %s (currency: %s).
Rules triggered:
%s
Explain in 2 sentences why this might be suspicious and what the user should check.`,
		p.Amount, p.MerchantName, p.TransactedAt, p.Currency,
		strings.Join(rules, "\n"))
}

// generateStubExplanation creates a deterministic explanation from rules.
func generateStubExplanation(p *AnomalyExplainPayload) string {
	if len(p.TriggeredRules) == 0 {
		return "No anomaly rules triggered."
	}

	reasons := make([]string, 0, len(p.TriggeredRules))
	for _, r := range p.TriggeredRules {
		reasons = append(reasons, r.Reason)
	}

	return fmt.Sprintf("This transaction of ₹%.2f at %s was flagged because: %s. "+
		"Please verify this transaction and mark it as safe or report it as fraud.",
		p.Amount, p.MerchantName, strings.Join(reasons, "; "))
}
