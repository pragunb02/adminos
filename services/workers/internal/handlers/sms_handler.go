package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/adminos/adminos/workers/internal/categorizer"
	"github.com/adminos/adminos/workers/internal/jobs"
	"github.com/adminos/adminos/workers/internal/normalizer"
)

// SmsPayload is the input for the SMS processing job.
type SmsPayload struct {
	UserID        string                 `json:"user_id"`
	SyncSessionID string                 `json:"sync_session_id"`
	ConnectionID  string                 `json:"connection_id"`
	Records       []normalizer.SmsRecord `json:"records"`
}

// SmsResult is the output of SMS processing.
type SmsResult struct {
	SyncSessionID  string `json:"sync_session_id"`
	TotalItems     int    `json:"total_items"`
	ProcessedItems int    `json:"processed_items"`
	DuplicateItems int    `json:"duplicate_items"`
	NetNewItems    int    `json:"net_new_items"`
	FailedItems    int    `json:"failed_items"`
}

// SmsHandler processes SMS batch jobs.
// Implements the JobHandler interface from the workflow package.
//
// Concurrency: SMS batches are max 100 records and normalization is CPU-bound
// (no I/O). Sequential processing is correct here — goroutine overhead would
// exceed the benefit. See gmail_handler.go for I/O-bound parallel processing.
type SmsHandler struct {
	mu           sync.Mutex // protects fingerprints map for thread safety
	fingerprints map[string]bool
}

func NewSmsHandler() *SmsHandler {
	return &SmsHandler{
		fingerprints: make(map[string]bool),
	}
}

func (h *SmsHandler) JobType() string     { return jobs.TypeSmsProcess }
func (h *SmsHandler) TriggerType() string { return jobs.TriggerEvent }

func (h *SmsHandler) Parse(payload json.RawMessage) (any, error) {
	var p SmsPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, fmt.Errorf("failed to parse SMS payload: %w", err)
	}
	if len(p.Records) == 0 {
		return nil, fmt.Errorf("empty batch")
	}
	return &p, nil
}

func (h *SmsHandler) Execute(ctx context.Context, payload any) (any, error) {
	p := payload.(*SmsPayload)

	result := &SmsResult{
		SyncSessionID: p.SyncSessionID,
		TotalItems:    len(p.Records),
	}

	for _, record := range p.Records {
		// Respect context cancellation — allows graceful shutdown
		select {
		case <-ctx.Done():
			return result, ctx.Err()
		default:
		}

		// Per-item timeout to prevent one bad record from blocking the batch
		itemCtx, cancel := context.WithTimeout(ctx, 5*time.Second)

		h.processRecord(itemCtx, p.UserID, record, result)

		cancel()
	}

	return result, nil
}

func (h *SmsHandler) processRecord(ctx context.Context, userID string, record normalizer.SmsRecord, result *SmsResult) {
	// Step 1: Normalize
	normalized, err := normalizer.Normalize(record, userID)
	if err != nil {
		log.Printf("[sms] Failed to normalize record: %v", err)
		result.FailedItems++
		result.ProcessedItems++
		return
	}

	// Step 2: Compute fingerprint
	normalized.Fingerprint = normalizer.ComputeFingerprint(
		userID,
		normalized.Amount,
		normalized.MerchantName,
		normalized.Date,
		normalized.AccountLast4,
	)

	// Step 3: Deduplicate (mutex-protected for future goroutine safety)
	h.mu.Lock()
	isDupe := h.fingerprints[normalized.Fingerprint]
	if !isDupe {
		h.fingerprints[normalized.Fingerprint] = true
	}
	h.mu.Unlock()

	if isDupe {
		result.DuplicateItems++
		result.ProcessedItems++
		return
	}

	// Step 4: Categorize
	catResult := categorizer.Categorize(normalized.MerchantName)
	normalized.Category = catResult.Category
	normalized.Confidence.Category = catResult.Confidence

	// Step 5: Would persist to DB here
	log.Printf("[sms] Processed: %s ₹%.2f %s (cat=%s, conf=%.2f)",
		normalized.MerchantName,
		normalized.Amount,
		normalized.Date.Format("2006-01-02"),
		normalized.Category,
		normalized.Confidence.Overall,
	)

	result.NetNewItems++
	result.ProcessedItems++
}

func (h *SmsHandler) Persist(ctx context.Context, result any) error {
	r := result.(*SmsResult)
	// TODO: Update sync_session in PostgreSQL with final counts
	log.Printf("[sms] Session %s complete: processed=%d, new=%d, dupes=%d, failed=%d",
		r.SyncSessionID, r.ProcessedItems, r.NetNewItems, r.DuplicateItems, r.FailedItems)
	return nil
}

func (h *SmsHandler) Notify(ctx context.Context, result any) error {
	// TODO: Send notification if this was a historical sync completion
	return nil
}
