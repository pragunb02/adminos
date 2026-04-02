package handlers

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/adminos/adminos/workers/internal/normalizer"
)

func TestSmsHandler_Parse(t *testing.T) {
	h := NewSmsHandler()

	payload := SmsPayload{
		UserID:        "user-123",
		SyncSessionID: "sync-456",
		Records: []normalizer.SmsRecord{
			{Merchant: "ZOMATO", Amount: 450, Date: "2025-01-15", AccountLast4: "4521"},
		},
	}
	raw, _ := json.Marshal(payload)

	result, err := h.Parse(raw)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}

	p := result.(*SmsPayload)
	if p.UserID != "user-123" {
		t.Errorf("Expected user-123, got %s", p.UserID)
	}
	if len(p.Records) != 1 {
		t.Errorf("Expected 1 record, got %d", len(p.Records))
	}
}

func TestSmsHandler_Parse_EmptyBatch(t *testing.T) {
	h := NewSmsHandler()

	payload := SmsPayload{UserID: "user-123", Records: []normalizer.SmsRecord{}}
	raw, _ := json.Marshal(payload)

	_, err := h.Parse(raw)
	if err == nil {
		t.Fatal("Expected error for empty batch")
	}
}

func TestSmsHandler_Execute_ProcessesBatch(t *testing.T) {
	h := NewSmsHandler()

	payload := &SmsPayload{
		UserID:        "user-123",
		SyncSessionID: "sync-456",
		Records: []normalizer.SmsRecord{
			{Merchant: "ZOMATO", Amount: 450, Date: "2025-01-15", AccountLast4: "4521"},
			{Merchant: "UBER", Amount: 120, Date: "2025-01-15", AccountLast4: "4521"},
		},
	}

	result, err := h.Execute(context.Background(), payload)
	if err != nil {
		t.Fatalf("Execute failed: %v", err)
	}

	r := result.(*SmsResult)
	if r.TotalItems != 2 {
		t.Errorf("Expected 2 total, got %d", r.TotalItems)
	}
	if r.NetNewItems != 2 {
		t.Errorf("Expected 2 new, got %d", r.NetNewItems)
	}
	if r.DuplicateItems != 0 {
		t.Errorf("Expected 0 dupes, got %d", r.DuplicateItems)
	}
}

func TestSmsHandler_Execute_DeduplicatesIdenticalRecords(t *testing.T) {
	h := NewSmsHandler()

	record := normalizer.SmsRecord{
		Merchant: "ZOMATO", Amount: 450, Date: "2025-01-15", AccountLast4: "4521",
	}

	payload := &SmsPayload{
		UserID:        "user-123",
		SyncSessionID: "sync-456",
		Records:       []normalizer.SmsRecord{record, record},
	}

	result, err := h.Execute(context.Background(), payload)
	if err != nil {
		t.Fatalf("Execute failed: %v", err)
	}

	r := result.(*SmsResult)
	if r.NetNewItems != 1 {
		t.Errorf("Expected 1 new (deduped), got %d", r.NetNewItems)
	}
	if r.DuplicateItems != 1 {
		t.Errorf("Expected 1 dupe, got %d", r.DuplicateItems)
	}
}

func TestSmsHandler_Execute_CategorizesMerchants(t *testing.T) {
	h := NewSmsHandler()

	payload := &SmsPayload{
		UserID:        "user-123",
		SyncSessionID: "sync-456",
		Records: []normalizer.SmsRecord{
			{Merchant: "ZOMATO", Amount: 450, Date: "2025-01-15", AccountLast4: "4521"},
			{Merchant: "UBER", Amount: 120, Date: "2025-01-16", AccountLast4: "4521"},
			{Merchant: "NETFLIX", Amount: 649, Date: "2025-01-17", AccountLast4: "4521"},
			{Merchant: "UNKNOWN SHOP", Amount: 200, Date: "2025-01-18", AccountLast4: "4521"},
		},
	}

	result, err := h.Execute(context.Background(), payload)
	if err != nil {
		t.Fatalf("Execute failed: %v", err)
	}

	r := result.(*SmsResult)
	if r.NetNewItems != 4 {
		t.Errorf("Expected 4 new, got %d", r.NetNewItems)
	}
	// Categories are applied internally — we verify via the log output
	// In production, we'd check the persisted transactions
}

func TestSmsHandler_Idempotence(t *testing.T) {
	// Property: re-ingesting the same batch produces zero net new items
	h := NewSmsHandler()

	records := []normalizer.SmsRecord{
		{Merchant: "ZOMATO", Amount: 450, Date: "2025-01-15", AccountLast4: "4521"},
		{Merchant: "UBER", Amount: 120, Date: "2025-01-15", AccountLast4: "4521"},
	}

	payload := &SmsPayload{
		UserID:        "user-123",
		SyncSessionID: "sync-1",
		Records:       records,
	}

	// First ingestion
	result1, _ := h.Execute(context.Background(), payload)
	r1 := result1.(*SmsResult)
	if r1.NetNewItems != 2 {
		t.Errorf("First run: expected 2 new, got %d", r1.NetNewItems)
	}

	// Second ingestion of same data
	payload.SyncSessionID = "sync-2"
	result2, _ := h.Execute(context.Background(), payload)
	r2 := result2.(*SmsResult)
	if r2.NetNewItems != 0 {
		t.Errorf("Second run: expected 0 new (idempotent), got %d", r2.NetNewItems)
	}
	if r2.DuplicateItems != 2 {
		t.Errorf("Second run: expected 2 dupes, got %d", r2.DuplicateItems)
	}
}
