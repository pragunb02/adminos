package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

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
	UserID         string `json:"user_id"`
	TotalItems     int    `json:"total_items"`
	ProcessedItems int    `json:"processed_items"`
	DuplicateItems int    `json:"duplicate_items"`
	NetNewItems    int    `json:"net_new_items"`
	FailedItems    int    `json:"failed_items"`

	// Enriched data for Persist
	Transactions []normalizer.NormalizedTransaction `json:"transactions,omitempty"`
	Fingerprints []FingerprintRecord                `json:"fingerprints,omitempty"`

	// Confidence distribution for extraction_quality
	HighConf      int     `json:"high_conf"`
	MedConf       int     `json:"med_conf"`
	LowConf       int     `json:"low_conf"`
	NeedsReview   int     `json:"needs_review"`
	AvgConfidence float64 `json:"avg_confidence"`
}

// SmsHandler processes SMS batch jobs.
// Implements the JobHandler interface from the workflow package.
//
// Concurrency: SMS batches are max 100 records and normalization is CPU-bound
// (no I/O). Sequential processing is correct here — goroutine overhead would
// exceed the benefit. See gmail_handler.go for I/O-bound parallel processing.
type SmsHandler struct {
	db           *pgxpool.Pool
	mu           sync.Mutex // protects fingerprints map for thread safety
	fingerprints map[string]bool
}

func NewSmsHandler(db *pgxpool.Pool) *SmsHandler {
	return &SmsHandler{
		db:           db,
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
		UserID:        p.UserID,
		TotalItems:    len(p.Records),
	}

	var confSum float64
	var confCount int

	for _, record := range p.Records {
		// Respect context cancellation — allows graceful shutdown
		select {
		case <-ctx.Done():
			return result, ctx.Err()
		default:
		}

		// Per-item timeout to prevent one bad record from blocking the batch
		itemCtx, cancel := context.WithTimeout(ctx, 5*time.Second)

		h.processRecord(itemCtx, p.UserID, record, result, &confSum, &confCount)

		cancel()
	}

	if confCount > 0 {
		result.AvgConfidence = confSum / float64(confCount)
	}

	return result, nil
}

func (h *SmsHandler) processRecord(ctx context.Context, userID string, record normalizer.SmsRecord, result *SmsResult, confSum *float64, confCount *int) {
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

	// Step 3: Deduplicate — use DB when available, in-memory otherwise
	isDupe, err := h.isDuplicate(ctx, userID, normalized.Fingerprint)
	if err != nil {
		log.Printf("[sms] Fingerprint check error, falling back to in-memory: %v", err)
		// Fall back to in-memory on DB error
		h.mu.Lock()
		isDupe = h.fingerprints[normalized.Fingerprint]
		if !isDupe {
			h.fingerprints[normalized.Fingerprint] = true
		}
		h.mu.Unlock()
	}

	if isDupe {
		result.DuplicateItems++
		result.ProcessedItems++
		return
	}

	// Mark in in-memory map for within-batch dedup (when DB returned not-dupe,
	// a second identical record in the same batch should still be caught)
	h.mu.Lock()
	alreadySeen := h.fingerprints[normalized.Fingerprint]
	h.fingerprints[normalized.Fingerprint] = true
	h.mu.Unlock()

	if alreadySeen {
		result.DuplicateItems++
		result.ProcessedItems++
		return
	}

	// Step 4: Categorize
	catResult := categorizer.Categorize(normalized.MerchantName)
	normalized.Category = catResult.Category
	normalized.Confidence.Category = catResult.Confidence

	// Track confidence distribution
	*confSum += normalized.Confidence.Overall
	*confCount++
	switch {
	case normalized.Confidence.Overall >= 0.8:
		result.HighConf++
	case normalized.Confidence.Overall >= 0.5:
		result.MedConf++
	case normalized.Confidence.Overall >= 0.3:
		result.LowConf++
	default:
		result.NeedsReview++
	}

	// Collect transaction and fingerprint for Persist
	entityID := uuid.New().String()
	result.Transactions = append(result.Transactions, *normalized)
	result.Fingerprints = append(result.Fingerprints, FingerprintRecord{
		UserID:      userID,
		Fingerprint: normalized.Fingerprint,
		SourceType:  "sms",
		EntityType:  "transaction",
		EntityID:    entityID,
	})

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

// isDuplicate checks the DB for an existing fingerprint when db is available.
// Falls back to in-memory map when db is nil.
func (h *SmsHandler) isDuplicate(ctx context.Context, userID, fingerprint string) (bool, error) {
	if h.db == nil {
		h.mu.Lock()
		isDupe := h.fingerprints[fingerprint]
		h.mu.Unlock()
		return isDupe, nil
	}
	var exists bool
	err := h.db.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM ingestion_fingerprints WHERE user_id = $1 AND fingerprint = $2)`,
		userID, fingerprint).Scan(&exists)
	return exists, err
}

func (h *SmsHandler) Persist(ctx context.Context, result any) error {
	r := result.(*SmsResult)

	if h.db == nil {
		// Degraded mode: log only
		log.Printf("[sms] Session %s complete: processed=%d, new=%d, dupes=%d, failed=%d",
			r.SyncSessionID, r.ProcessedItems, r.NetNewItems, r.DuplicateItems, r.FailedItems)
		return nil
	}

	tx, err := h.db.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	// Insert transactions
	for _, txn := range r.Transactions {
		_, err := tx.Exec(ctx, `
			INSERT INTO transactions (id, user_id, source_type, type, amount, currency,
				merchant_name, merchant_raw, account_last4, payment_method, category,
				category_source, transacted_at, created_at, updated_at)
			VALUES (gen_random_uuid(), $1, $2, 'debit', $3, 'INR',
				$4, $5, $6, $7, $8, 'rules', $9, now(), now())`,
			txn.UserID, txn.SourceType, txn.Amount,
			txn.MerchantName, txn.MerchantRaw, txn.AccountLast4, txn.PaymentMethod,
			txn.Category, txn.Date)
		if err != nil {
			return fmt.Errorf("insert transaction: %w", err)
		}
	}

	// Insert fingerprints
	for _, fp := range r.Fingerprints {
		_, err := tx.Exec(ctx, `
			INSERT INTO ingestion_fingerprints (user_id, fingerprint, source_type, entity_type, entity_id)
			VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (user_id, fingerprint) DO NOTHING`,
			fp.UserID, fp.Fingerprint, fp.SourceType, fp.EntityType, fp.EntityID)
		if err != nil {
			return fmt.Errorf("insert fingerprint: %w", err)
		}
	}

	// Update sync session
	_, err = tx.Exec(ctx, `
		UPDATE sync_sessions SET
			status = 'completed', processed_items = $2, failed_items = $3,
			duplicate_items = $4, net_new_items = $5, completed_at = now(), updated_at = now()
		WHERE id = $1`,
		r.SyncSessionID, r.ProcessedItems, r.FailedItems, r.DuplicateItems, r.NetNewItems)
	if err != nil {
		return fmt.Errorf("update sync session: %w", err)
	}

	// Insert extraction quality
	_, err = tx.Exec(ctx, `
		INSERT INTO extraction_quality (user_id, sync_session_id, source_type,
			total_records, high_confidence, medium_confidence, low_confidence,
			needs_review, avg_confidence)
		VALUES ($1, $2, 'sms', $3, $4, $5, $6, $7, $8)`,
		r.UserID, r.SyncSessionID,
		r.TotalItems, r.HighConf, r.MedConf, r.LowConf, r.NeedsReview, r.AvgConfidence)
	if err != nil {
		return fmt.Errorf("insert extraction quality: %w", err)
	}

	return tx.Commit(ctx)
}

func (h *SmsHandler) Notify(ctx context.Context, result any) error {
	// TODO: Send notification if this was a historical sync completion
	return nil
}
