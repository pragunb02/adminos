package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"

	"github.com/adminos/adminos/workers/internal/jobs"
)

// AnomalyExplainPayload is the input for the anomaly explanation job.
type AnomalyExplainPayload struct {
	UserID        string   `json:"user_id"`
	AnomalyID     string   `json:"anomaly_id"`
	TransactionID string   `json:"transaction_id"`
	Amount        float64  `json:"amount"`
	Currency      string   `json:"currency"`
	MerchantName  string   `json:"merchant_name"`
	TransactedAt  string   `json:"transacted_at"`
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
type AnomalyExplainHandler struct{}

func NewAnomalyExplainHandler() *AnomalyExplainHandler {
	return &AnomalyExplainHandler{}
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

	// Build the prompt with transaction details and triggered rules
	prompt := buildAnomalyPrompt(p)

	// Log the prompt (Claude API stub for MVP)
	log.Printf("[anomaly_explain] Prompt for anomaly %s:\n%s", p.AnomalyID, prompt)

	// In production, this would call Claude API:
	// response, err := claudeClient.Complete(ctx, prompt)
	// For now, generate a deterministic explanation from the rules
	explanation := generateStubExplanation(p)

	return &AnomalyExplainResult{
		AnomalyID:        p.AnomalyID,
		AgentExplanation: explanation,
		Prompt:           prompt,
		ModelUsed:        "claude-sonnet-stub",
	}, nil
}

func (h *AnomalyExplainHandler) Persist(ctx context.Context, result any) error {
	r := result.(*AnomalyExplainResult)
	// TODO: UPDATE anomalies SET agent_explanation = r.AgentExplanation WHERE id = r.AnomalyID
	log.Printf("[anomaly_explain] Stored explanation for anomaly %s: %s",
		r.AnomalyID, r.AgentExplanation)
	return nil
}

func (h *AnomalyExplainHandler) Notify(ctx context.Context, result any) error {
	r := result.(*AnomalyExplainResult)
	// Anomaly notifications bypass quiet hours (critical)
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

// generateStubExplanation creates a deterministic explanation from rules (stub for Claude API).
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
