package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"net/mail"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adminos/adminos/workers/internal/bookings"
	"github.com/adminos/adminos/workers/internal/gmail"
	"github.com/adminos/adminos/workers/internal/jobs"
	"github.com/adminos/adminos/workers/internal/normalizer"
	"github.com/adminos/adminos/workers/internal/receipts"
	"github.com/adminos/adminos/workers/internal/tokencrypt"
)

// GmailPayload is the input for the Gmail ingestion job.
type GmailPayload struct {
	UserID        string `json:"user_id"`
	SyncSessionID string `json:"sync_session_id"`
	ConnectionID  string `json:"connection_id"`
	// HistoryID is the push notification's history ID: informational only. A sync always starts
	// from the history ID stored on the connection (the notification's is the mailbox's *new*
	// position; starting there would skip the very change it announces).
	HistoryID    string `json:"history_id,omitempty"`
	GmailAddress string `json:"gmail_address"`
}

// GmailEmail represents a single email fetched from Gmail API. It lives in memory only, for
// extraction (D-33): it is never serialised, stored or logged.
type GmailEmail struct {
	MessageID string `json:"-"`
	From      string `json:"-"`
	Subject   string `json:"-"`
	Body      string `json:"-"`
	Date      string `json:"-"`
}

// ExtractedFinancialData holds parsed financial info from an email.
type ExtractedFinancialData struct {
	Type         string  `json:"type"` // "transaction" or "bill"
	Merchant     string  `json:"merchant"`
	Amount       float64 `json:"amount"`
	Date         string  `json:"date"`
	BillerName   string  `json:"biller_name,omitempty"`
	BillDueDate  string  `json:"bill_due_date,omitempty"`
	BillType     string  `json:"bill_type,omitempty"`
	AccountLast4 string  `json:"account_last4,omitempty"`
	Confidence   float64 `json:"confidence"`
	// Direction is "debit" or "credit" for transactions; "" when the email doesn't say clearly (D-43).
	Direction string `json:"direction,omitempty"`
	// OccurredAt is when the money moved: the email's Date header, else the date in the body.
	OccurredAt time.Time `json:"occurred_at"`
	// MessageID is the Gmail message ID: the idempotency key of the ingest event.
	MessageID string `json:"message_id"`
}

// GmailResult is the output of Gmail processing.
type GmailResult struct {
	SyncSessionID     string `json:"sync_session_id"`
	UserID            string `json:"user_id"`
	ConnectionID      string `json:"connection_id"`
	TotalEmails       int    `json:"total_emails"`
	TransactionsFound int    `json:"transactions_found"`
	BillsFound        int    `json:"bills_found"`
	DuplicateItems    int    `json:"duplicate_items"`
	NetNewItems       int    `json:"net_new_items"`
	FailedItems       int    `json:"failed_items"`
	// NewHistoryID is Gmail's real history ID after this sync (AOS-401); "" leaves the stored one.
	NewHistoryID string `json:"new_history_id,omitempty"`
	SyncMode     string `json:"sync_mode,omitempty"`
	NotFinancial int    `json:"not_financial"`

	// Order receipts (AOS-903): read by a per-sender parser, never turned into transactions.
	ReceiptsFound int `json:"receipts_found"`
	// NotReceipts counts mail from receipt senders that isn't a receipt (promotions, status updates).
	NotReceipts int `json:"not_receipts"`
	// ReceiptFailures counts receipts whose numbers didn't add up (a changed format shows here).
	ReceiptFailures int `json:"receipt_failures"`
	// PayLaterSkipped counts provider emails that are neither a bill nor a purchase (AOS-909).
	PayLaterSkipped int             `json:"pay_later_skipped"`
	Receipts        []ReceiptRecord `json:"receipts,omitempty"`
	// Travel bookings (AOS-910): the journey date and amount, tied to the payment by the API.
	BookingsFound int             `json:"bookings_found"`
	Bookings      []BookingRecord `json:"bookings,omitempty"`

	// Enriched data for Persist. Transactions go to the common intake as events (AOS-205, D-21):
	// the API's pipeline does dedup, transfers, refunds and categories.
	Events       []IngestEventRecord `json:"events,omitempty"`
	Bills        []BillRecord        `json:"bills,omitempty"`
	Fingerprints []FingerprintRecord `json:"fingerprints,omitempty"`

	// Confidence distribution
	HighConf      int     `json:"high_conf"`
	MedConf       int     `json:"med_conf"`
	LowConf       int     `json:"low_conf"`
	NeedsReview   int     `json:"needs_review"`
	AvgConfidence float64 `json:"avg_confidence"`
}

// GmailConfig is what the Gmail handler needs besides its database and Gmail client.
type GmailConfig struct {
	ClientID     string
	ClientSecret string
	// Cipher reads and writes the OAuth tokens in the API's TokenEncryptor format (D-59). Required.
	Cipher *tokencrypt.Cipher
	// Now is the clock (tests inject one); nil means time.Now.
	Now func() time.Time
	// Receipts picks the order-receipt parser per sender (AOS-903); nil means receipts.Default().
	Receipts *receipts.Registry
}

// GmailHandler processes Gmail ingestion jobs.
// Implements the JobHandler interface from the workflow package.
type GmailHandler struct {
	db           *pgxpool.Pool
	conns        gmailConnectionStore
	gmailClient  *gmail.Client
	cipher       *tokencrypt.Cipher
	clientID     string
	clientSecret string
	now          func() time.Time
	receipts     *receipts.Registry
	bookings     *bookings.Registry
	mu           sync.Mutex
	fingerprints map[string]bool
}

func NewGmailHandler(db *pgxpool.Pool, gmailClient *gmail.Client, cfg GmailConfig) *GmailHandler {
	var conns gmailConnectionStore
	if db != nil {
		conns = &pgConnectionStore{db: db}
	}
	return newGmailHandler(db, conns, gmailClient, cfg)
}

func newGmailHandler(db *pgxpool.Pool, conns gmailConnectionStore, gmailClient *gmail.Client, cfg GmailConfig) *GmailHandler {
	now := cfg.Now
	if now == nil {
		now = time.Now
	}
	registry := cfg.Receipts
	if registry == nil {
		registry = receipts.Default()
	}
	return &GmailHandler{
		receipts:     registry,
		bookings:     bookings.Default(),
		db:           db,
		conns:        conns,
		gmailClient:  gmailClient,
		cipher:       cfg.Cipher,
		clientID:     cfg.ClientID,
		clientSecret: cfg.ClientSecret,
		now:          now,
		fingerprints: make(map[string]bool),
	}
}

func (h *GmailHandler) JobType() string     { return jobs.TypeGmailIngest }
func (h *GmailHandler) TriggerType() string { return jobs.TriggerEvent }

func (h *GmailHandler) Parse(payload json.RawMessage) (any, error) {
	var p GmailPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, fmt.Errorf("failed to parse Gmail payload: %w", err)
	}
	for _, f := range []struct{ name, value string }{
		{"user_id", p.UserID}, {"connection_id", p.ConnectionID}, {"sync_session_id", p.SyncSessionID},
	} {
		if f.value == "" {
			return nil, fmt.Errorf("%s is required", f.name)
		}
		if _, err := uuid.Parse(f.value); err != nil {
			return nil, fmt.Errorf("%s must be a UUID", f.name)
		}
	}
	return &p, nil
}

// Execute syncs one Gmail connection. A failure is recorded on the sync session and the connection
// (a short reason, no content) and returned, so the job is retried (D-43).
func (h *GmailHandler) Execute(ctx context.Context, payload any) (any, error) {
	p, ok := payload.(*GmailPayload)
	if !ok {
		return nil, fmt.Errorf("gmail ingestion: unexpected payload %T", payload)
	}
	// No silent fallback: if Gmail can't be reached the job fails and is retried (AOS-205).
	if h.gmailClient == nil || h.conns == nil || h.cipher == nil {
		return nil, errGmailNotConfigured
	}
	result, err := h.execute(ctx, p)
	if err != nil {
		f := syncFailure{ConnectionID: p.ConnectionID, SyncSessionID: p.SyncSessionID, Reason: failureReason(err)}
		if recErr := h.conns.RecordFailure(context.WithoutCancel(ctx), f); recErr != nil {
			log.Printf("[gmail] Could not record the failed sync of connection %s: %v", p.ConnectionID, recErr)
		}
		return nil, err
	}
	return result, nil
}

func (h *GmailHandler) execute(ctx context.Context, p *GmailPayload) (*GmailResult, error) {
	conn, err := h.conns.Load(ctx, p.ConnectionID)
	if err != nil {
		return nil, err
	}
	if conn.Status != "connected" {
		return nil, errNotConnected
	}
	accessToken, err := h.accessToken(ctx, p.ConnectionID, conn)
	if err != nil {
		return nil, fmt.Errorf("gmail access token: %w", err)
	}
	fetched, err := h.fetchMail(ctx, accessToken, conn.HistoryID)
	if err != nil {
		return nil, fmt.Errorf("fetch gmail messages: %w", err)
	}

	result := &GmailResult{
		SyncSessionID: p.SyncSessionID,
		UserID:        p.UserID,
		ConnectionID:  p.ConnectionID,
		TotalEmails:   len(fetched.Emails),
		FailedItems:   fetched.Gone,
		NewHistoryID:  fetched.NewHistoryID,
		SyncMode:      string(fetched.Mode),
		NotFinancial:  fetched.NotFinancial,
	}

	var confSum float64
	var confCount int

	for _, email := range fetched.Emails {
		if err := ctx.Err(); err != nil {
			return nil, err
		}

		// Pay-later providers first (AOS-909): Flipkart and Amazon send both receipts and pay-later bills.
		if h.processPayLater(p.UserID, email, result) {
			continue
		}

		// A travel booking is evidence about a payment too, with the journey date (AOS-910).
		if parser := h.bookings.ParserFor(email.From); parser != nil {
			processBooking(p.UserID, parser, email, result)
			continue
		}

		// A receipt is evidence about a payment, never a second spend: receipt senders never reach
		// the bank-email parser (AOS-903).
		if parser := h.receipts.ParserFor(email.From); parser != nil {
			processReceipt(p.UserID, parser, email, result)
			continue
		}

		extracted := parseFinancialEmail(email)
		if extracted == nil {
			continue
		}

		if extracted.Type == "bill" {
			result.BillsFound++
			h.processBill(p.UserID, extracted, result)
		} else {
			result.TransactionsFound++
			processTransaction(p.UserID, extracted, result, &confSum, &confCount)
		}
	}

	if confCount > 0 {
		result.AvgConfidence = confSum / float64(confCount)
	}
	log.Printf("[gmail] Connection %s: %s sync, %d financial emails (%d receipts, %d unreadable receipts), %d skipped by the sender filters",
		p.ConnectionID, fetched.Mode, len(fetched.Emails), result.ReceiptsFound, result.ReceiptFailures, fetched.NotFinancial)
	return result, nil
}

// failureReason is the short, content-free reason stored with a failed sync.
func failureReason(err error) string {
	var retryable *gmail.RetryableError
	switch {
	case errors.Is(err, errAccessRevoked):
		return errAccessRevoked.Error()
	case errors.Is(err, errTokenUnreadable):
		return "stored gmail token can't be decrypted"
	case errors.Is(err, errNoTokens), errors.Is(err, errNotConnected), errors.Is(err, errConnectionNotFound):
		return "gmail is not connected"
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		return "gmail sync interrupted"
	case errors.As(err, &retryable):
		return "gmail temporarily unavailable"
	default:
		return "gmail sync failed"
	}
}

// processTransaction turns an extracted email transaction into an ingest event (AOS-205).
// Duplicates are decided at insert time (unique per user + source + message ID) and by the pipeline.
func processTransaction(userID string, data *ExtractedFinancialData, result *GmailResult, confSum *float64, confCount *int) {
	if data.Direction == "" {
		result.NeedsReview++ // never guess debit (D-43)
		log.Printf("[gmail] Skipped message %s: direction unclear", data.MessageID)
		return
	}
	if data.MessageID == "" || data.OccurredAt.IsZero() {
		result.FailedItems++
		return
	}

	*confSum += data.Confidence
	*confCount++
	switch {
	case data.Confidence >= 0.8:
		result.HighConf++
	case data.Confidence >= 0.5:
		result.MedConf++
	default:
		result.LowConf++
	}

	result.Events = append(result.Events, IngestEventRecord{
		UserID:      userID,
		SourceRef:   data.MessageID,
		OccurredAt:  data.OccurredAt,
		Direction:   data.Direction,
		Amount:      strconv.FormatFloat(data.Amount, 'f', 2, 64),
		MerchantRaw: optional(truncate(data.Merchant, 255), "Unknown"),
		AccountHint: optional(data.AccountLast4, ""),
	})
}

// processReceipt reads an order receipt into structured fields (AOS-903). Not a receipt (a promotion)
// is skipped; a receipt that doesn't add up is a failure, logged by parser and message ID only (D-33).
func processReceipt(userID string, parser receipts.Parser, email GmailEmail, result *GmailResult) {
	date, err := mail.ParseDate(email.Date)
	if err != nil {
		date = time.Time{}
	}
	r, err := parser.Parse(receipts.Email{MessageID: email.MessageID, From: email.From, Subject: email.Subject, Body: email.Body, Date: date})
	switch {
	case errors.Is(err, receipts.ErrNotAReceipt):
		result.NotReceipts++
		return
	case err != nil:
		result.ReceiptFailures++
		result.FailedItems++
		log.Printf("[gmail] Receipt in message %s not read by the %s parser: %v", email.MessageID, parser.Name(), err)
		return
	}
	if email.MessageID == "" {
		result.FailedItems++
		return
	}
	result.ReceiptsFound++
	result.Receipts = append(result.Receipts, newReceiptRecord(userID, email.MessageID, r))
}

func optional(s, empty string) *string {
	if s == "" || s == empty {
		return nil
	}
	return &s
}

func truncate(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

func (h *GmailHandler) processBill(userID string, data *ExtractedFinancialData, result *GmailResult) {
	// bills.due_date is NOT NULL: a bill without a readable due date used to fail the whole batch
	// (every email in it, AOS-503). Skip just that bill, without logging its content.
	dueDate, ok := normalizeDueDate(data.BillDueDate)
	if !ok {
		log.Printf("[gmail] Bill skipped: no readable due date")
		result.FailedItems++
		return
	}
	due, _ := time.Parse("2006-01-02", dueDate)
	fp := normalizer.ComputeFingerprint(userID, data.Amount, data.BillerName, due, "bill")

	isDupe, err := h.isDuplicate(context.Background(), userID, fp)
	if err != nil {
		log.Printf("[gmail] Bill fingerprint check error, falling back to in-memory: %v", err)
		h.mu.Lock()
		isDupe = h.fingerprints[fp]
		if !isDupe {
			h.fingerprints[fp] = true
		}
		h.mu.Unlock()
	}

	if isDupe {
		result.DuplicateItems++
		return
	}

	// Within-batch dedup
	h.mu.Lock()
	alreadySeen := h.fingerprints[fp]
	h.fingerprints[fp] = true
	h.mu.Unlock()

	if alreadySeen {
		result.DuplicateItems++
		return
	}

	billID := uuid.New().String()
	result.Bills = append(result.Bills, BillRecord{
		ID:         billID,
		UserID:     userID,
		BillerName: data.BillerName,
		Amount:     data.Amount,
		Currency:   "INR",
		DueDate:    dueDate,
		Status:     "upcoming",
		SourceType: "gmail",
		BillType:   billTypeOrOther(data.BillType),
		AccountRef: optional(data.AccountLast4, ""),
	})
	result.Fingerprints = append(result.Fingerprints, FingerprintRecord{
		UserID:      userID,
		Fingerprint: fp,
		SourceType:  "gmail",
		EntityType:  "bill",
		EntityID:    billID,
	})

	log.Printf("[gmail] Bill found: due=%s type=%s (conf=%.2f)", dueDate, billTypeOrOther(data.BillType), data.Confidence)

	result.NetNewItems++
}

// isDuplicate checks the DB for an existing fingerprint when db is available.
func (h *GmailHandler) isDuplicate(ctx context.Context, userID, fingerprint string) (bool, error) {
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

func (h *GmailHandler) Persist(ctx context.Context, result any) error {
	r := result.(*GmailResult)

	if h.db == nil {
		log.Printf("[gmail] Session %s complete: emails=%d, txns=%d, bills=%d, dupes=%d, new=%d",
			r.SyncSessionID, r.TotalEmails, r.TransactionsFound, r.BillsFound,
			r.DuplicateItems, r.NetNewItems)
		return nil
	}

	tx, err := h.db.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)

	// Transactions → common intake (AOS-205). A re-synced email is a duplicate, not a second row.
	for _, e := range r.Events {
		tag, err := tx.Exec(ctx, `
			INSERT INTO ingest_events (user_id, source, source_ref, occurred_at, direction, amount, currency,
				merchant_raw, account_hint, payment_method, metadata, status)
			VALUES ($1, 'gmail', $2, $3, $4::ingest_direction, $5::numeric, 'INR', $6, $7, $8::payment_method, $9::jsonb, 'pending')
			ON CONFLICT (user_id, source, source_ref) DO NOTHING`,
			e.UserID, e.SourceRef, e.OccurredAt, e.Direction, e.Amount, e.MerchantRaw, e.AccountHint, e.PaymentMethod, e.Metadata)
		if err != nil {
			return fmt.Errorf("insert ingest event: %w", err)
		}
		if tag.RowsAffected() == 1 {
			r.NetNewItems++
		} else {
			r.DuplicateItems++
		}
	}

	if err := persistReceipts(ctx, tx, r); err != nil {
		return err
	}
	if err := persistBookings(ctx, tx, r); err != nil {
		return err
	}

	// Insert bills. A pay-later bill already known for that provider and due date (its SMS, AOS-909) is not repeated.
	for _, bill := range r.Bills {
		_, err := tx.Exec(ctx, `
			INSERT INTO bills (id, user_id, bill_type, biller_name, amount, currency,
				due_date, status, detection_source, account_ref, created_at, updated_at)
			SELECT $1::uuid, $2::uuid, $9::bill_type, $3::text, $4::numeric, $5::text, $6::date, $7::bill_status, $8::source_type, $10::text, now(), now()
			WHERE $9::text <> 'pay_later' OR NOT EXISTS (
				SELECT 1 FROM bills b WHERE b.user_id = $2::uuid AND b.bill_type = 'pay_later'
					AND lower(b.biller_name) = lower($3::text) AND b.due_date = $6::date AND b.status <> 'cancelled')`,
			bill.ID, bill.UserID, bill.BillerName, bill.Amount, bill.Currency,
			bill.DueDate, bill.Status, bill.SourceType, billTypeOrOther(bill.BillType), bill.AccountRef)
		if err != nil {
			return fmt.Errorf("insert bill: %w", err)
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

	// The connection's new start point and last success (AOS-401). The history ID only moves
	// forward: a slower, older sync finishing late never rewinds it; a non-Gmail value (the old
	// "history_<time>") is replaced; "" keeps what is stored.
	if r.ConnectionID != "" {
		_, err = tx.Exec(ctx, `
			UPDATE user_connections SET
				history_id = CASE
					WHEN $2::text = '' THEN history_id
					WHEN history_id ~ '^[0-9]{1,20}$' AND $2::text ~ '^[0-9]{1,20}$'
						AND history_id::numeric > $2::text::numeric THEN history_id
					ELSE $2::text END,
				last_synced_at = now(), last_sync_status = 'success', last_error = NULL,
				total_synced = total_synced + $3, updated_at = now()
			WHERE id = $1`,
			r.ConnectionID, r.NewHistoryID, r.NetNewItems)
		if err != nil {
			return fmt.Errorf("update user connection: %w", err)
		}
	}

	// Update sync session
	_, err = tx.Exec(ctx, `
		UPDATE sync_sessions SET
			status = 'completed', processed_items = $2, failed_items = $3,
			duplicate_items = $4, net_new_items = $5, completed_at = now(), updated_at = now()
		WHERE id = $1`,
		r.SyncSessionID, r.TransactionsFound+r.BillsFound+r.ReceiptsFound+r.BookingsFound, r.FailedItems,
		r.DuplicateItems, r.NetNewItems)
	if err != nil {
		return fmt.Errorf("update sync session: %w", err)
	}

	// Insert extraction quality
	_, err = tx.Exec(ctx, `
		INSERT INTO extraction_quality (user_id, sync_session_id, source_type,
			total_records, high_confidence, medium_confidence, low_confidence,
			needs_review, avg_confidence)
		VALUES ($1, $2, 'gmail', $3, $4, $5, $6, $7, $8)`,
		r.UserID, r.SyncSessionID,
		r.TotalEmails, r.HighConf, r.MedConf, r.LowConf, r.NeedsReview, r.AvgConfidence)
	if err != nil {
		return fmt.Errorf("insert extraction quality: %w", err)
	}

	return tx.Commit(ctx)
}

func (h *GmailHandler) Notify(ctx context.Context, result any) error {
	// TODO: Send notification on sync completion
	return nil
}

// --- Email parsing helpers ---

// Financial email patterns for Indian banks and services
var (
	amountPatterns = []*regexp.Regexp{
		regexp.MustCompile(`(?i)(?:rs\.?|inr|₹)\s*([\d,]+\.?\d*)`),
		regexp.MustCompile(`(?i)([\d,]+\.?\d*)\s*(?:rs\.?|inr|₹)`),
		regexp.MustCompile(`(?i)amount[:\s]+(?:rs\.?|inr|₹)?\s*([\d,]+\.?\d*)`),
		regexp.MustCompile(`(?i)(?:debited|credited|paid|charged)\s+(?:rs\.?|inr|₹)?\s*([\d,]+\.?\d*)`),
	}

	datePatterns = []*regexp.Regexp{
		regexp.MustCompile(`(\d{4}-\d{2}-\d{2})`),
		regexp.MustCompile(`(\d{2}/\d{2}/\d{4})`),
		regexp.MustCompile(`(\d{2}-\d{2}-\d{4})`),
		regexp.MustCompile(`(?i)(\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\w*\s+\d{4})`),
	}

	dueDatePatterns = []*regexp.Regexp{
		regexp.MustCompile(`(?i)(?:due\s*(?:date|on|by)|pay\s*by|repay\s*by)[:\s]+(\d{1,2}-[A-Za-z]{3}-\d{4})`),
		regexp.MustCompile(`(?i)due\s*(?:date|on|by)[:\s]+(\d{2}/\d{2}/\d{4})`),
		regexp.MustCompile(`(?i)due\s*(?:date|on|by)[:\s]+(\d{4}-\d{2}-\d{2})`),
		regexp.MustCompile(`(?i)due\s*(?:date|on|by)[:\s]+(\d{1,2}\s+\w+\s+\d{4})`),
		regexp.MustCompile(`(?i)pay\s*(?:by|before)[:\s]+(\d{2}/\d{2}/\d{4})`),
	}

	accountPatterns = []*regexp.Regexp{
		regexp.MustCompile(`(?i)(?:a/c|account|acct)[:\s]*(?:no\.?\s*)?[xX*]*(\d{4})`),
		regexp.MustCompile(`(?i)(?:card|cc)[:\s]*(?:no\.?\s*)?[xX*]*(\d{4})`),
	}

	billKeywords = []string{
		"bill", "due date", "payment due", "minimum due", "total due",
		"electricity bill", "water bill", "broadband bill", "phone bill",
		"credit card statement", "card statement", "amount due",
	}

	transactionKeywords = []string{
		"debited", "credited", "transaction", "payment", "purchase",
		"transferred", "received", "spent", "charged", "paid",
	}

	merchantPatterns = []*regexp.Regexp{
		regexp.MustCompile(`(?i)(?:at|to|from|merchant|payee)[:\s]+([A-Za-z][\w\s&'.,-]{2,30})`),
		regexp.MustCompile(`(?i)(?:payment to|paid to|transfer to)\s+([A-Za-z][\w\s&'.,-]{2,30})`),
	}
)

// parseFinancialEmail extracts financial data from an email.
func parseFinancialEmail(email GmailEmail) *ExtractedFinancialData {
	content := email.Subject + " " + email.Body
	contentLower := strings.ToLower(content)

	isBill := false
	for _, kw := range billKeywords {
		if strings.Contains(contentLower, kw) {
			isBill = true
			break
		}
	}

	isTransaction := false
	for _, kw := range transactionKeywords {
		if strings.Contains(contentLower, kw) {
			isTransaction = true
			break
		}
	}

	if !isBill && !isTransaction {
		return nil
	}

	amount, amountConf := extractAmount(content)
	if amount <= 0 {
		return nil
	}

	date := extractDate(content)
	if date == "" {
		date = email.Date
	}

	accountLast4 := extractAccountLast4(content)

	if isBill {
		dueDate := extractDueDate(content)
		billerName := extractMerchant(content, email.From)
		confidence := computeEmailConfidence(amount, amountConf, billerName, dueDate)
		return &ExtractedFinancialData{
			Type: "bill", Merchant: billerName, Amount: amount, Date: date,
			BillerName: billerName, BillDueDate: dueDate, BillType: detectBillType(contentLower),
			AccountLast4: accountLast4, Confidence: confidence,
		}
	}

	merchant := extractMerchant(content, email.From)
	confidence := computeEmailConfidence(amount, amountConf, merchant, "")
	return &ExtractedFinancialData{
		Type: "transaction", Merchant: merchant, Amount: amount, Date: date,
		AccountLast4: accountLast4, Confidence: confidence,
		Direction: detectDirection(content), OccurredAt: occurredAt(email.Date, extractDate(content)),
		MessageID: email.MessageID,
	}
}

func extractAmount(content string) (float64, float64) {
	for _, pattern := range amountPatterns {
		matches := pattern.FindStringSubmatch(content)
		if len(matches) >= 2 {
			amtStr := strings.ReplaceAll(matches[1], ",", "")
			amt, err := strconv.ParseFloat(amtStr, 64)
			if err == nil && amt > 0 {
				return math.Round(amt*100) / 100, 0.85
			}
		}
	}
	return 0, 0
}

func extractDate(content string) string {
	for _, pattern := range datePatterns {
		matches := pattern.FindStringSubmatch(content)
		if len(matches) >= 2 {
			return matches[1]
		}
	}
	return ""
}

func extractDueDate(content string) string {
	for _, pattern := range dueDatePatterns {
		matches := pattern.FindStringSubmatch(content)
		if len(matches) >= 2 {
			return matches[1]
		}
	}
	return ""
}

func extractAccountLast4(content string) string {
	for _, pattern := range accountPatterns {
		matches := pattern.FindStringSubmatch(content)
		if len(matches) >= 2 {
			return matches[1]
		}
	}
	return ""
}

func extractMerchant(content string, from string) string {
	for _, pattern := range merchantPatterns {
		matches := pattern.FindStringSubmatch(content)
		if len(matches) >= 2 {
			merchant := strings.TrimSpace(matches[1])
			if len(merchant) > 2 {
				return merchant
			}
		}
	}
	if from != "" {
		if idx := strings.Index(from, "<"); idx > 0 {
			name := strings.TrimSpace(from[:idx])
			if len(name) > 2 {
				return name
			}
		}
		return from
	}
	return "Unknown"
}

func computeEmailConfidence(amount float64, amountConf float64, merchant string, dueDate string) float64 {
	scores := []float64{amountConf}
	if merchant != "" && merchant != "Unknown" {
		scores = append(scores, 0.7)
	} else {
		scores = append(scores, 0.3)
	}
	if dueDate != "" {
		scores = append(scores, 0.8)
	}
	sum := 0.0
	for _, s := range scores {
		sum += s
	}
	avg := sum / float64(len(scores))
	return math.Round(avg*100) / 100
}

func parseDateOrNow(dateStr string) time.Time {
	formats := []string{"2006-01-02", "02/01/2006", "02-01-2006", time.RFC3339}
	for _, f := range formats {
		if t, err := time.Parse(f, dateStr); err == nil {
			return t
		}
	}
	return time.Now()
}

// Direction words (D-43). What happened to *your* account decides: a bank email often says both
// "debited from your a/c" and "credited to <payee>", so an account-anchored phrase wins.
var (
	anchoredDirection = regexp.MustCompile(`(?i)\b(debited|credited)\s+(?:from|to|in(?:to)?)\s+(?:your\s+)?(?:a/c|ac|acct|account|card|credit card|bank account)\b`)
	debitWords        = regexp.MustCompile(`(?i)\b(debited|spent|paid|charged|purchase|withdrawn|sent)\b`)
	creditWords       = regexp.MustCompile(`(?i)\b(credited|received|refund(?:ed)?|deposited|cashback|reversed)\b`)
)

// detectDirection returns "debit", "credit", or "" when the email doesn't say clearly.
func detectDirection(content string) string {
	if m := anchoredDirection.FindStringSubmatch(content); m != nil {
		if strings.EqualFold(m[1], "credited") {
			return "credit"
		}
		return "debit"
	}
	debit, credit := debitWords.MatchString(content), creditWords.MatchString(content)
	switch {
	case debit && !credit:
		return "debit"
	case credit && !debit:
		return "credit"
	default:
		return ""
	}
}

var ist = time.FixedZone("IST", 5*60*60+30*60)

// occurredAt prefers the email's Date header (alerts are sent within minutes, with a time);
// otherwise the date in the body at 00:00 India time. Zero if neither parses.
func occurredAt(header, bodyDate string) time.Time {
	if t, err := mail.ParseDate(header); err == nil {
		return t
	}
	for _, f := range []string{"2006-01-02", "02/01/2006", "02-01-2006", "2 Jan 2006", "2 January 2006"} {
		if t, err := time.ParseInLocation(f, bodyDate, ist); err == nil {
			return t
		}
	}
	return time.Time{}
}

// dueDateLayouts are the due-date forms bills use (dueDatePatterns). Numeric dates are Indian: day first.
var dueDateLayouts = []string{"2-Jan-2006", "02-Jan-2006", "2006-01-02", "02/01/2006", "2/1/2006", "02-01-2006", "2-1-2006", "2 January 2006", "2 Jan 2006", "January 2, 2006", "Jan 2, 2006"}

// normalizeDueDate turns an extracted due date into yyyy-mm-dd, or reports that it can't (AOS-503).
// Postgres would read "05/11/2026" month-first, so dates are never passed through as written.
func normalizeDueDate(raw string) (string, bool) {
	raw = strings.Join(strings.Fields(raw), " ")
	if raw == "" {
		return "", false
	}
	for _, layout := range dueDateLayouts {
		if t, err := time.Parse(layout, raw); err == nil {
			return t.Format("2006-01-02"), true
		}
	}
	return "", false
}

// billTypes maps the words a bill email uses to bills.bill_type, most specific first.
var billTypes = []struct {
	kind  string
	words []string
}{
	{"credit_card", []string{"credit card", "card statement", "card account"}},
	{"electricity", []string{"electricity", "power bill", "bescom", "tata power", "adani electricity", "msedcl"}},
	{"internet", []string{"broadband", "fiber", "fibre", "internet"}},
	{"mobile", []string{"postpaid", "mobile bill", "phone bill"}},
	{"insurance", []string{"insurance", "premium"}},
	{"emi", []string{"emi", "loan"}},
	{"water", []string{"water bill"}},
	{"gas", []string{"gas bill", "piped gas", "lpg"}},
	{"rent", []string{"rent"}},
}

// billTypePatterns are billTypes as whole-word patterns ("emi" must not match "premium"), compiled once.
var billTypePatterns = func() []struct {
	kind string
	re   *regexp.Regexp
} {
	var out []struct {
		kind string
		re   *regexp.Regexp
	}
	for _, bt := range billTypes {
		quoted := make([]string, len(bt.words))
		for i, w := range bt.words {
			quoted[i] = regexp.QuoteMeta(w)
		}
		out = append(out, struct {
			kind string
			re   *regexp.Regexp
		}{bt.kind, regexp.MustCompile(`\b(?:` + strings.Join(quoted, "|") + `)\b`)})
	}
	return out
}()

// detectBillType guesses the bill's type from its (lower-case) text; "" when nothing fits.
func detectBillType(contentLower string) string {
	for _, p := range billTypePatterns {
		if p.re.MatchString(contentLower) {
			return p.kind
		}
	}
	return ""
}

func billTypeOrOther(kind string) string {
	if kind == "" {
		return "other"
	}
	return kind
}
