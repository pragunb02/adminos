package handlers

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/adminos/adminos/workers/internal/workflow"
)

// ── Waste Scoring Tests ──

func TestWasteScore_NoEngagement(t *testing.T) {
	sub := workflow.SubscriptionData{
		ID:              "sub-1",
		FirstBilledDate: time.Now().AddDate(0, -6, 0),
		LastBilledDate:  time.Now(),
		Status:          "active",
	}
	signals := workflow.GmailSignals{
		LoginEmailCount: 0,
		UsageEmailCount: 0,
	}

	score := ComputeWasteScore(sub, signals)
	if score < 0.7 {
		t.Errorf("Expected high waste score for no engagement, got %.2f", score)
	}
	if score > 1.0 {
		t.Errorf("Score %.2f exceeds 1.0", score)
	}
}

func TestWasteScore_HighEngagement(t *testing.T) {
	sub := workflow.SubscriptionData{
		ID:              "sub-2",
		FirstBilledDate: time.Now().AddDate(0, -1, 0),
		LastBilledDate:  time.Now(),
		Status:          "active",
	}
	signals := workflow.GmailSignals{
		LoginEmailCount: 10,
		UsageEmailCount: 5,
	}

	score := ComputeWasteScore(sub, signals)
	if score > 0.3 {
		t.Errorf("Expected low waste score for high engagement, got %.2f", score)
	}
}

func TestWasteScore_MediumEngagement(t *testing.T) {
	sub := workflow.SubscriptionData{
		ID:              "sub-3",
		FirstBilledDate: time.Now().AddDate(0, -4, 0),
		LastBilledDate:  time.Now(),
		Status:          "active",
	}
	signals := workflow.GmailSignals{
		LoginEmailCount: 2,
		UsageEmailCount: 0,
	}

	score := ComputeWasteScore(sub, signals)
	if score < 0.0 || score > 1.0 {
		t.Errorf("Score %.2f out of bounds", score)
	}
}

func TestWasteScore_NewSubscription(t *testing.T) {
	sub := workflow.SubscriptionData{
		ID:              "sub-4",
		FirstBilledDate: time.Now().AddDate(0, 0, -15), // 15 days ago
		LastBilledDate:  time.Now(),
		Status:          "active",
	}
	signals := workflow.GmailSignals{
		LoginEmailCount: 0,
		UsageEmailCount: 0,
	}

	score := ComputeWasteScore(sub, signals)
	// New subscription with no engagement — should still be bounded
	if score < 0.0 || score > 1.0 {
		t.Errorf("Score %.2f out of bounds", score)
	}
}

func TestWasteScoringHandler_Parse(t *testing.T) {
	h := NewWasteScoringHandler(nil)
	payload := WasteScoringPayload{
		UserID: "user-1",
		Subscriptions: []workflow.SubscriptionData{
			{ID: "sub-1", FirstBilledDate: time.Now().AddDate(0, -3, 0), LastBilledDate: time.Now()},
		},
	}
	raw, _ := json.Marshal(payload)

	result, err := h.Parse(raw)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}
	p := result.(*WasteScoringPayload)
	if p.UserID != "user-1" {
		t.Errorf("Expected user-1, got %s", p.UserID)
	}
}

func TestWasteScoringHandler_Execute(t *testing.T) {
	h := NewWasteScoringHandler(nil)
	payload := &WasteScoringPayload{
		UserID: "user-1",
		Subscriptions: []workflow.SubscriptionData{
			{
				ID:              "sub-1",
				FirstBilledDate: time.Now().AddDate(0, -6, 0),
				LastBilledDate:  time.Now(),
				Status:          "active",
			},
			{
				ID:              "sub-2",
				FirstBilledDate: time.Now().AddDate(0, -1, 0),
				LastBilledDate:  time.Now(),
				Status:          "active",
			},
		},
		GmailSignalsMap: map[string]workflow.GmailSignals{
			"sub-1": {LoginEmailCount: 0, UsageEmailCount: 0},
			"sub-2": {LoginEmailCount: 5, UsageEmailCount: 3},
		},
	}

	result, err := h.Execute(context.Background(), payload)
	if err != nil {
		t.Fatalf("Execute failed: %v", err)
	}

	r := result.(*WasteScoringBatchResult)
	if r.Total != 2 {
		t.Errorf("Expected 2 total, got %d", r.Total)
	}
	if len(r.Results) != 2 {
		t.Fatalf("Expected 2 results, got %d", len(r.Results))
	}

	// sub-1 should be flagged (no engagement, 6 months)
	if !r.Results[0].IsFlagged {
		t.Error("Expected sub-1 to be flagged")
	}
	// sub-2 should not be flagged (high engagement, 1 month)
	if r.Results[1].IsFlagged {
		t.Error("Expected sub-2 to not be flagged")
	}
}

func TestMonthsBetween(t *testing.T) {
	now := time.Now()

	tests := []struct {
		name     string
		start    time.Time
		expected int
	}{
		{"same month", now, 0},
		{"1 month ago", now.AddDate(0, -1, 0), 1},
		{"6 months ago", now.AddDate(0, -6, 0), 6},
		{"12 months ago", now.AddDate(-1, 0, 0), 12},
		{"future date", now.AddDate(0, 1, 0), 0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := monthsBetween(tt.start, now)
			if result != tt.expected {
				t.Errorf("Expected %d months, got %d", tt.expected, result)
			}
		})
	}
}
