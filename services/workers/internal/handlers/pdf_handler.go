package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"

	"github.com/adminos/adminos/workers/internal/categorizer"
	"github.com/adminos/adminos/workers/internal/jobs"
	"github.com/adminos/adminos/workers/internal/normalizer"
	"github.com/adminos/adminos/workers/internal/workflow"
)

// PdfPayload is the input for the PDF parse result processing job.
type PdfPayload struct {
	UserID        string                      `json:"user_id"`
	SyncSessionID string                      `json:"sync_session_id"`
	ConnectionID  string                      `json:"connection_id"`
	StorageKey    string                      `json:"storage_key"`
	BankCode      string                      `json:"bank_code"`
	Transactions  []PdfExtractedTransaction   `json:"transactions"`
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
	BankCode       string `json:"bank_code"`
	TotalItems     int    `json:"total_items"`
	ProcessedItems int    `json:"processed_items"`
	DuplicateItems int    `json:"duplicate_items"`
	NetNewItems    int    `json:"net_new_items"`
	FailedItems    int    `json:"failed_items"`
}

// PdfHandler processes parsed PDF results through the normalization pipeline.
// Implements the JobHandler interface from the workflow package.
//
// Concurrency: For large PDFs (500+ transactions), process in parallel
// using errgroup.SetLimit(10). Fingerprints map is mutex-protected.
type PdfHandler struct {
	mu           sync.Mutex
	fingerprints map[string]bool
}

func NewPdfHandler() *PdfHandler {
	return &PdfHandler{
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
		BankCode:      p.BankCode,
		TotalItems:    len(p.Transactions),
	}

	// TODO: For large PDFs (500+ transactions), process in parallel using errgroup:
	//   g, ctx := errgroup.WithContext(ctx)
	//   g.SetLimit(10)
	//   Use sync.Mutex on result counters and h.fingerprints map
	for _, txn := range p.Transactions {
		select {
		case <-ctx.Done():
			return result, ctx.Err()
		default:
		}

		// Convert PDF transaction to SmsRecord for normalization pipeline reuse
		record := normalizer.SmsRecord{
			Merchant:     txn.Description,
			Amount:       txn.Amount,
			Date:         txn.Date,
			AccountLast4: extractAccountFromBankCode(p.BankCode),
		}

		// Step 1: Normalize
		normalized, err := normalizer.Normalize(record, p.UserID)
		if err != nil {
			log.Printf("[pdf] Failed to normalize: %v", err)
			result.FailedItems++
			result.ProcessedItems++
			continue
		}

		// Override source type
		normalized.SourceType = "pdf"

		// Adjust confidence based on PDF extraction quality
		normalized.Confidence.Overall = adjustPdfConfidence(
			normalized.Confidence, txn.Confidence,
		)

		// Step 2: Compute fingerprint
		normalized.Fingerprint = normalizer.ComputeFingerprint(
			p.UserID,
			normalized.Amount,
			normalized.MerchantName,
			normalized.Date,
			normalized.AccountLast4,
		)

		// Step 3: Deduplicate (mutex-protected for goroutine safety)
		h.mu.Lock()
		isDupe := h.fingerprints[normalized.Fingerprint]
		if !isDupe {
			h.fingerprints[normalized.Fingerprint] = true
		}
		h.mu.Unlock()

		if isDupe {
			result.DuplicateItems++
			result.ProcessedItems++
			continue
		}

		// Step 4: Categorize
		catResult := categorizer.Categorize(normalized.MerchantName)
		normalized.Category = catResult.Category
		normalized.Confidence.Category = catResult.Confidence

		// Step 5: Would persist to transactions table
		log.Printf("[pdf] Transaction: %s ₹%.2f %s (bank=%s, cat=%s, conf=%.2f)",
			normalized.MerchantName,
			normalized.Amount,
			normalized.Date.Format("2006-01-02"),
			p.BankCode,
			normalized.Category,
			normalized.Confidence.Overall,
		)

		result.NetNewItems++
		result.ProcessedItems++
	}

	return result, nil
}

func (h *PdfHandler) Persist(ctx context.Context, result any) error {
	r := result.(*PdfResult)
	// TODO: Update sync_session in PostgreSQL with final counts
	log.Printf("[pdf] Session %s complete: bank=%s, processed=%d, new=%d, dupes=%d, failed=%d",
		r.SyncSessionID, r.BankCode, r.ProcessedItems, r.NetNewItems,
		r.DuplicateItems, r.FailedItems)
	return nil
}

func (h *PdfHandler) Notify(ctx context.Context, result any) error {
	r := result.(*PdfResult)
	// TODO: Send notification: "N new transactions imported from [bank]"
	if r.NetNewItems > 0 {
		log.Printf("[pdf] Notification: %d new transactions imported from %s",
			r.NetNewItems, r.BankCode)
	}
	return nil
}

// adjustPdfConfidence blends normalizer confidence with PDF extraction confidence.
func adjustPdfConfidence(normConf workflow.ConfidenceScores, pdfConf float64) float64 {
	// PDF extraction confidence typically 0.7-0.9
	blended := normConf.Overall*0.6 + pdfConf*0.4
	if blended > 1.0 {
		blended = 1.0
	}
	return float64(int(blended*100)) / 100
}

// extractAccountFromBankCode returns a placeholder account identifier from bank code.
func extractAccountFromBankCode(bankCode string) string {
	// In production, this would come from the PDF parser's account extraction
	return ""
}
