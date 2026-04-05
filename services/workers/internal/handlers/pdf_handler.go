package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adminos/adminos/workers/internal/categorizer"
	"github.com/adminos/adminos/workers/internal/jobs"
	"github.com/adminos/adminos/workers/internal/normalizer"
	"github.com/adminos/adminos/workers/internal/workflow"
)

// PdfPayload is the input for the PDF parse result processing job.
type PdfPayload struct {
	UserID        string                    `json:"user_id"`
	SyncSessionID string                    `json:"sync_session_id"`
	ConnectionID  string                    `json:"connection_id"`
	StorageKey    string                    `json:"storage_key"`
	BankCode      string                    `json:"bank_code"`
	Transactions  []PdfExtractedTransaction `json:"transactions"`
}

// PdfExtractedTransaction is a single transaction from the Python PDF parser.
type PdfExtractedTransaction struct {
	Date        string  `json:"date"`
	Description string  `json:"description"`
	Amount      float64 `json:"amount"`
	Type        string  `json:"type"` // "debit" or "credit"
	Balance     float64 `json:"balance,omitempty"`
	Confidence  float64 `json:"confidence"`
}

// PdfResult is the output of PDF result processing.
type PdfResult struct {
	SyncSessionID  string `json:"sync_session_id"`
	UserID         string `json:"user_id"`
	BankCode       string `json:"bank_code"`
	TotalItems     int    `json:"total_items"`
	ProcessedItems int    `json:"processed_items"`
	DuplicateItems int    `json:"duplicate_items"`
	NetNewItems    int    `json:"net_new_items"`
	FailedItems    int    `json:"failed_items"`

	// Enriched data for Persist
	Transactions []normalizer.NormalizedTransaction `json:"transactions,omitempty"`
	Fingerprints []FingerprintRecord                `json:"fingerprints,omitempty"`
}

// PdfHandler processes parsed PDF results through the normalization pipeline.
// Implements the JobHandler interface from the workflow package.
type PdfHandler struct {
	db           *pgxpool.Pool
	mu           sync.Mutex
	fingerprints map[string]bool
}

func NewPdfHandler(db *pgxpool.Pool) *PdfHandler {
	return &PdfHandler{
		db:           db,
		fingerprints: make(map[string]bool),
	}
}

func (h *PdfHandler) JobType() string    { return jobs.TypePdfParse }
func (h *PdfHandler) TriggerType() string { return jobs.TriggerEvent }

func (h *PdfHandler) Parse(payload json.RawMessage) (any, error) {
	var p PdfPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, fmt.Errorf("failed to parse PDF payload: %w", err)
	}
	if len(p.Transactions) == 0 {
		return nil, fmt.Errorf("no transactions in PDF result")
	}
	return &p, nil
}

func (h *PdfHandler) Execute(ctx context.Context, payload any) (any, error) {
	p := payload.(*PdfPayload)

	result := &PdfResult{
		SyncSessionID: p.SyncSessionID,
		UserID:        p.UserID,
		BankCode:      p.BankCode,
		TotalItems:    len(p.Transactions),
	}

	for _, txn := range p.Transactions {
		select {
		case <-ctx.Done():
			return result, ctx.Err()
		default:
		}

		record := normalizer.SmsRecord{
			Merchant:     txn.Description,
			Amount:       txn.Amount,
			Date:         txn.Date,
			AccountLast4: extractAccountFromBankCode(p.BankCode),
		}

		normalized, err := normalizer.Normalize(record, p.UserID)
		if err != nil {
			log.Printf("[pdf] Failed to normalize: %v", err)
			result.FailedItems++
			result.ProcessedItems++
			continue
		}

		normalized.SourceType = "pdf"
		normalized.Confidence.Overall = adjustPdfConfidence(normalized.Confidence, txn.Confidence)

		normalized.Fingerprint = normalizer.ComputeFingerprint(
			p.UserID, normalized.Amount, normalized.MerchantName,
			normalized.Date, normalized.AccountLast4,
		)

		// Deduplicate — use DB when available, in-memory for within-batch
		isDupe, err := h.isDuplicate(ctx, p.UserID, normalized.Fingerprint)
		if err != nil {
			log.Printf("[pdf] Fingerprint check error, falling back to in-memory: %v", err)
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
			continue
		}

		// Within-batch dedup (DB doesn't see items not yet persisted)
		h.mu.Lock()
		alreadySeen := h.fingerprints[normalized.Fingerprint]
		h.fingerprints[normalized.Fingerprint] = true
		h.mu.Unlock()

		if alreadySeen {
			result.DuplicateItems++
			result.ProcessedItems++
			continue
		}

		catResult := categorizer.Categorize(normalized.MerchantName)
		normalized.Category = catResult.Category
		normalized.Confidence.Category = catResult.Confidence

		entityID := uuid.New().String()
		result.Transactions = append(result.Transactions, *normalized)
		result.Fingerprints = append(result.Fingerprints, FingerprintRecord{
			UserID:      p.UserID,
			Fingerprint: normalized.Fingerprint,
			SourceType:  "pdf",
			EntityType:  "transaction",
			EntityID:    entityID,
		})

		log.Printf("[pdf] Transaction: %s ₹%.2f %s (bank=%s, cat=%s, conf=%.2f)",
			normalized.MerchantName, normalized.Amount,
			normalized.Date.Format("2006-01-02"),
			p.BankCode, normalized.Category, normalized.Confidence.Overall,
		)

		result.NetNewItems++
		result.ProcessedItems++
	}

	return result, nil
}

// isDuplicate checks the DB for an existing fingerprint when db is available.
func (h *PdfHandler) isDuplicate(ctx context.Context, userID, fingerprint string) (bool, error) {
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

func (h *PdfHandler) Persist(ctx context.Context, result any) error {
	r := result.(*PdfResult)

	if h.db == nil {
		log.Printf("[pdf] Session %s complete: bank=%s, processed=%d, new=%d, dupes=%d, failed=%d",
			r.SyncSessionID, r.BankCode, r.ProcessedItems, r.NetNewItems,
			r.DuplicateItems, r.FailedItems)
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

	return tx.Commit(ctx)
}

func (h *PdfHandler) Notify(ctx context.Context, result any) error {
	r := result.(*PdfResult)
	if r.NetNewItems > 0 {
		log.Printf("[pdf] Notification: %d new transactions imported from %s",
			r.NetNewItems, r.BankCode)
	}
	return nil
}

// adjustPdfConfidence blends normalizer confidence with PDF extraction confidence.
func adjustPdfConfidence(normConf workflow.ConfidenceScores, pdfConf float64) float64 {
	blended := normConf.Overall*0.6 + pdfConf*0.4
	if blended > 1.0 {
		blended = 1.0
	}
	return float64(int(blended*100)) / 100
}

// extractAccountFromBankCode returns a placeholder account identifier from bank code.
func extractAccountFromBankCode(bankCode string) string {
	return ""
}
