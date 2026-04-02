package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"testing"
	"time"

	"github.com/adminos/adminos/workers/internal/normalizer"
)

// Property: Re-ingesting the same SMS batch always produces zero net new transactions.
// This must hold for ANY combination of merchants, amounts, dates, and accounts.
// Validates Requirement 5.5
func TestProperty_DeduplicationIdempotence(t *testing.T) {
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))

	merchants := []string{"ZOMATO", "UBER", "NETFLIX", "AMAZON", "HDFC-ATM", "UNKNOWN SHOP", "SBI CARD"}
	accounts := []string{"4521", "8890", "1234", "5678"}

	// Run 20 random scenarios
	for scenario := 0; scenario < 20; scenario++ {
		h := NewSmsHandler()

		// Generate random batch (1-10 records)
		batchSize := rng.Intn(10) + 1
		records := make([]normalizer.SmsRecord, batchSize)
		for i := 0; i < batchSize; i++ {
			records[i] = normalizer.SmsRecord{
				Merchant:     merchants[rng.Intn(len(merchants))],
				Amount:       float64(rng.Intn(10000)+1) / 100.0, // ₹0.01 to ₹100.00
				Date:         fmt.Sprintf("2025-01-%02d", rng.Intn(28)+1),
				AccountLast4: accounts[rng.Intn(len(accounts))],
			}
		}

		payload := &SmsPayload{
			UserID:        "property-test-user",
			SyncSessionID: fmt.Sprintf("sync-%d-run1", scenario),
			Records:       records,
		}

		// First ingestion
		result1, err := h.Execute(context.Background(), payload)
		if err != nil {
			t.Fatalf("Scenario %d, run 1 failed: %v", scenario, err)
		}
		r1 := result1.(*SmsResult)

		// Second ingestion of exact same data
		payload.SyncSessionID = fmt.Sprintf("sync-%d-run2", scenario)
		result2, err := h.Execute(context.Background(), payload)
		if err != nil {
			t.Fatalf("Scenario %d, run 2 failed: %v", scenario, err)
		}
		r2 := result2.(*SmsResult)

		// PROPERTY: second run must produce zero net new items
		if r2.NetNewItems != 0 {
			t.Errorf("Scenario %d: idempotence violated — second run produced %d net new items (expected 0). Batch: %v",
				scenario, r2.NetNewItems, records)
		}

		// All items in second run should be duplicates (minus any that failed)
		expectedDupes := r2.ProcessedItems - r2.FailedItems
		if r2.DuplicateItems != expectedDupes {
			t.Errorf("Scenario %d: expected %d dupes in second run, got %d",
				scenario, expectedDupes, r2.DuplicateItems)
		}

		// Total processed should equal batch size in both runs
		if r1.ProcessedItems != batchSize {
			t.Errorf("Scenario %d: run 1 processed %d, expected %d", scenario, r1.ProcessedItems, batchSize)
		}
		if r2.ProcessedItems != batchSize {
			t.Errorf("Scenario %d: run 2 processed %d, expected %d", scenario, r2.ProcessedItems, batchSize)
		}
	}
}

// Property: Two transactions with identical (user_id, amount, merchant, date, account_last4)
// always produce the same fingerprint. Validates Requirement 5.1
func TestProperty_FingerprintDeterminism(t *testing.T) {
	rng := rand.New(rand.NewSource(42)) // fixed seed for reproducibility

	for i := 0; i < 50; i++ {
		userID := fmt.Sprintf("user-%d", rng.Intn(100))
		amount := float64(rng.Intn(100000)) / 100.0
		merchant := fmt.Sprintf("Merchant-%d", rng.Intn(50))
		date := time.Date(2025, time.Month(rng.Intn(12)+1), rng.Intn(28)+1, 0, 0, 0, 0, time.UTC)
		account := fmt.Sprintf("%04d", rng.Intn(10000))

		fp1 := normalizer.ComputeFingerprint(userID, amount, merchant, date, account)
		fp2 := normalizer.ComputeFingerprint(userID, amount, merchant, date, account)

		if fp1 != fp2 {
			t.Errorf("Iteration %d: same inputs produced different fingerprints: %s vs %s", i, fp1, fp2)
		}
		if len(fp1) != 64 {
			t.Errorf("Iteration %d: fingerprint length %d, expected 64", i, len(fp1))
		}
	}
}

// Property: Changing any single field in the fingerprint input produces a different fingerprint.
func TestProperty_FingerprintSensitivity(t *testing.T) {
	baseDate := time.Date(2025, 1, 15, 0, 0, 0, 0, time.UTC)
	baseFP := normalizer.ComputeFingerprint("user-1", 450.0, "Zomato", baseDate, "4521")

	// Change user
	fp := normalizer.ComputeFingerprint("user-2", 450.0, "Zomato", baseDate, "4521")
	if fp == baseFP {
		t.Error("Different user should produce different fingerprint")
	}

	// Change amount
	fp = normalizer.ComputeFingerprint("user-1", 451.0, "Zomato", baseDate, "4521")
	if fp == baseFP {
		t.Error("Different amount should produce different fingerprint")
	}

	// Change merchant
	fp = normalizer.ComputeFingerprint("user-1", 450.0, "Swiggy", baseDate, "4521")
	if fp == baseFP {
		t.Error("Different merchant should produce different fingerprint")
	}

	// Change date
	fp = normalizer.ComputeFingerprint("user-1", 450.0, "Zomato", baseDate.AddDate(0, 0, 1), "4521")
	if fp == baseFP {
		t.Error("Different date should produce different fingerprint")
	}

	// Change account
	fp = normalizer.ComputeFingerprint("user-1", 450.0, "Zomato", baseDate, "8890")
	if fp == baseFP {
		t.Error("Different account should produce different fingerprint")
	}
}

// Property: Processing order doesn't affect deduplication outcome.
// If we process [A, B, A], we should get 2 new + 1 dupe regardless of order.
func TestProperty_DeduplicationOrderIndependence(t *testing.T) {
	recordA := normalizer.SmsRecord{Merchant: "ZOMATO", Amount: 450, Date: "2025-01-15", AccountLast4: "4521"}
	recordB := normalizer.SmsRecord{Merchant: "UBER", Amount: 120, Date: "2025-01-15", AccountLast4: "4521"}

	// Order 1: [A, B, A]
	h1 := NewSmsHandler()
	p1 := &SmsPayload{UserID: "user-1", SyncSessionID: "s1", Records: []normalizer.SmsRecord{recordA, recordB, recordA}}
	r1, _ := h1.Execute(context.Background(), p1)
	res1 := r1.(*SmsResult)

	// Order 2: [A, A, B]
	h2 := NewSmsHandler()
	p2 := &SmsPayload{UserID: "user-1", SyncSessionID: "s2", Records: []normalizer.SmsRecord{recordA, recordA, recordB}}
	r2, _ := h2.Execute(context.Background(), p2)
	res2 := r2.(*SmsResult)

	// Order 3: [B, A, A]
	h3 := NewSmsHandler()
	p3 := &SmsPayload{UserID: "user-1", SyncSessionID: "s3", Records: []normalizer.SmsRecord{recordB, recordA, recordA}}
	r3, _ := h3.Execute(context.Background(), p3)
	res3 := r3.(*SmsResult)

	// All orders should produce same counts
	for i, res := range []*SmsResult{res1, res2, res3} {
		if res.NetNewItems != 2 {
			t.Errorf("Order %d: expected 2 new, got %d", i+1, res.NetNewItems)
		}
		if res.DuplicateItems != 1 {
			t.Errorf("Order %d: expected 1 dupe, got %d", i+1, res.DuplicateItems)
		}
	}
}

// Helper to marshal payload for Parse testing
func marshalPayload(p *SmsPayload) json.RawMessage {
	raw, _ := json.Marshal(p)
	return raw
}
