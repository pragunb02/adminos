package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adminos/adminos/workers/internal/categorizer"
	"github.com/adminos/adminos/workers/internal/gmail"
	"github.com/adminos/adminos/workers/internal/jobs"
	"github.com/adminos/adminos/workers/internal/normalizer"
)

// GmailPayload is the input for the Gmail ingestion job.
type GmailPayload struct {
	UserID        string `json:"user_id"`
	SyncSessionID string `json:"sync_session_id"`
	ConnectionID  string `json:"connection_id"`
	HistoryID     string `json:"history_id,omitempty"`
	GmailAddress  string `json:"gmail_address"`
}

// GmailEmail represents a single email fetched from Gmail API.
type GmailEmail struct {
	MessageID string `json:"message_id"`
	From      string `json:"from"`
	Subject   string `json:"subject"`
	Body      string `json:"body"`
	Date      string `json:"date"`
}

// ExtractedFinancialData holds parsed financial info from an email.
type ExtractedFinancialData struct {
	Type         string  `json:"type"` // "transaction" or "bill"
	Merchant     string  `json:"merchant"`
	Amount       float64 `json:"amount"`
	Date         string  `json:"date"`
	BillerName   string  `json:"biller_name,omitempty"`
	BillDueDate  string  `json:"bill_due_date,omitempty"`
	AccountLast4 string  `json:"account_last4,omitempty"`
	Confidence   float64 `json:"confidence"`
}

// GmailResult is the output of Gmail processing.
type GmailResult struct {
	SyncSessionID    string `json:"sync_session_id"`
	UserID           string `json:"user_id"`
	ConnectionID     string `json:"connection_id"`
	TotalEmails      int    `json:"total_emails"`
	TransactionsFound int   `json:"transactions_found"`
	BillsFound       int    `json:"bills_found"`
	DuplicateItems   int    `json:"duplicate_items"`
	NetNewItems      int    `json:"net_new_items"`
	FailedItems      int    `json:"failed_items"`
	NewHistoryID     string `json:"new_history_id,omitempty"`

	// Enriched data for Persist
	Transactions []normalizer.NormalizedTransaction `json:"transactions,omitempty"`
	Bills        []BillRecord                       `json:"bills,omitempty"`
	Fingerprints []FingerprintRecord                `json:"fingerprints,omitempty"`

	// Confidence distribution
	HighConf      int     `json:"high_conf"`
	MedConf       int     `json:"med_conf"`
	LowConf       int     `json:"low_conf"`
	NeedsReview   int     `json:"needs_review"`
	AvgConfidence float64 `json:"avg_confidence"`
}

// GmailHandler processes Gmail ingestion jobs.
// Implements the JobHandler interface from the workflow package.
type GmailHandler struct {
	db           *pgxpool.Pool
	gmailClient  *gmail.Client
	clientID     string
	clientSecret string
	mu           sync.Mutex
	fingerprints map[string]bool
}

func NewGmailHandler(db *pgxpool.Pool, gmailClient *gmail.Client, clientID, clientSecret string) *GmailHandler {
	return &GmailHandler{
		db:           db,
		gmailClient:  gmailClient,
		clientID:     clientID,
		clientSecret: clientSecret,
		fingerprints: make(map[string]bool),
	}
}

func (h *GmailHandler) JobType() string    { return jobs.TypeGmailIngest }
func (h *GmailHandler) TriggerType() string { return jobs.TriggerEvent }

func (h *GmailHandler) Parse(payload json.RawMessage) (any, error) {
	var p GmailPayload
	if err := json.Unmarshal(payload, &p); err != nil {
		return nil, fmt.Errorf("failed to parse Gmail payload: %w", err)
	}
	if p.UserID == "" {
		return nil, fmt.Errorf("user_id is required")
	}
	if p.ConnectionID == "" {
		return nil, fmt.Errorf("connection_id is required")
	}
	return &p, nil
}

func (h *GmailHandler) Execute(ctx context.Context, payload any) (any, error) {
	p := payload.(*GmailPayload)

	// Get a valid access token for the Gmail API
	var accessToken string
	if h.gmailClient != nil && h.db != nil && p.ConnectionID != "" {
		var err error
		accessToken, err = h.getAccessToken(ctx, p.ConnectionID)
		if err != nil {
			log.Printf("[gmail] Failed to get access token: %v — falling back to stub emails", err)
		}
	}

	var emails []GmailEmail
	if accessToken != "" && h.gmailClient != nil {
		fetched, err := h.fetchRealEmails(ctx, accessToken, p.HistoryID)
		if err != nil {
			log.Printf("[gmail] Failed to fetch real emails: %v — falling back to stub", err)
			emails = fetchFinancialEmails(p.GmailAddress, p.HistoryID)
		} else {
			emails = fetched
		}
	} else {
		emails = fetchFinancialEmails(p.GmailAddress, p.HistoryID)
	}

	result := &GmailResult{
		SyncSessionID: p.SyncSessionID,
		UserID:        p.UserID,
		ConnectionID:  p.ConnectionID,
		TotalEmails:   len(emails),
	}

	var confSum float64
	var confCount int

	for _, email := range emails {
		select {
		case <-ctx.Done():
			return result, ctx.Err()
		default:
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
			h.processTransaction(p.UserID, extracted, result, &confSum, &confCount)
		}
	}

	if confCount > 0 {
		result.AvgConfidence = confSum / float64(confCount)
	}

	result.NewHistoryID = fmt.Sprintf("history_%d", time.Now().Unix())
	return result, nil
}

// getAccessToken retrieves a valid OAuth access token for the given connection.
// If the token is expired (with 5-minute buffer), it refreshes via the Gmail client.
// TODO: Token encryption/decryption — currently stores/reads plaintext tokens.
func (h *GmailHandler) getAccessToken(ctx context.Context, connectionID string) (string, error) {
	var accessToken, refreshToken string
	var expiresAt *time.Time

	err := h.db.QueryRow(ctx,
		`SELECT access_token, refresh_token, token_expires_at FROM user_connections WHERE id = $1`,
		connectionID).Scan(&accessToken, &refreshToken, &expiresAt)
	if err != nil {
		return "", fmt.Errorf("query connection tokens: %w", err)
	}

	if accessToken == "" {
		return "", fmt.Errorf("no access token stored for connection %s", connectionID)
	}

	// Check if token is still valid with 5-minute buffer
	if expiresAt != nil && time.Now().Before(expiresAt.Add(-5*time.Minute)) {
		return accessToken, nil
	}

	// Token expired — refresh it
	if refreshToken == "" {
		return "", fmt.Errorf("no refresh token available for connection %s", connectionID)
	}

	tokenResp, err := h.gmailClient.RefreshToken(ctx, h.clientID, h.clientSecret, refreshToken)
	if err != nil {
		// If refresh token is revoked, mark connection as error
		if _, ok := err.(*gmail.TokenRevokedError); ok {
			_, dbErr := h.db.Exec(ctx,
				`UPDATE user_connections SET status = 'error', last_error = $2, updated_at = now() WHERE id = $1`,
				connectionID, err.Error())
			if dbErr != nil {
				log.Printf("[gmail] Failed to update connection status: %v", dbErr)
			}
		}
		return "", fmt.Errorf("refresh token: %w", err)
	}

	// Store new token in DB
	newExpiry := time.Now().Add(time.Duration(tokenResp.ExpiresIn) * time.Second)
	_, err = h.db.Exec(ctx,
		`UPDATE user_connections SET access_token = $2, token_expires_at = $3, updated_at = now() WHERE id = $1`,
		connectionID, tokenResp.AccessToken, newExpiry)
	if err != nil {
		log.Printf("[gmail] Failed to store refreshed token: %v", err)
	}

	return tokenResp.AccessToken, nil
}

// fetchRealEmails uses the Gmail API client to fetch financial emails.
func (h *GmailHandler) fetchRealEmails(ctx context.Context, accessToken, historyID string) ([]GmailEmail, error) {
	query := "category:updates OR category:promotions subject:(transaction OR payment OR bill OR statement OR receipt)"

	listResp, err := h.gmailClient.ListMessages(ctx, accessToken, query, "")
	if err != nil {
		return nil, fmt.Errorf("list messages: %w", err)
	}

	var emails []GmailEmail
	for _, entry := range listResp.Messages {
		msg, err := h.gmailClient.GetMessage(ctx, accessToken, entry.ID)
		if err != nil {
			log.Printf("[gmail] Failed to get message %s: %v", entry.ID, err)
			continue
		}

		email := GmailEmail{MessageID: msg.ID}
		for _, h := range msg.Payload.Headers {
			switch h.Name {
			case "From":
				email.From = h.Value
			case "Subject":
				email.Subject = h.Value
			case "Date":
				email.Date = h.Value
			}
		}
		email.Body = msg.Snippet
		emails = append(emails, email)
	}

	return emails, nil
}

func (h *GmailHandler) processTransaction(userID string, data *ExtractedFinancialData, result *GmailResult, confSum *float64, confCount *int) {
	record := normalizer.SmsRecord{
		Merchant:     data.Merchant,
		Amount:       data.Amount,
		Date:         data.Date,
		AccountLast4: data.AccountLast4,
	}

	normalized, err := normalizer.Normalize(record, userID)
	if err != nil {
		log.Printf("[gmail] Failed to normalize transaction: %v", err)
		result.FailedItems++
		return
	}

	normalized.SourceType = "gmail"
	normalized.Confidence.Overall = math.Round(
		(normalized.Confidence.Overall*0.7+data.Confidence*0.3)*100) / 100

	normalized.Fingerprint = normalizer.ComputeFingerprint(
		userID, normalized.Amount, normalized.MerchantName,
		normalized.Date, normalized.AccountLast4,
	)

	isDupe, err := h.isDuplicate(context.Background(), userID, normalized.Fingerprint)
	if err != nil {
		log.Printf("[gmail] Fingerprint check error, falling back to in-memory: %v", err)
		h.mu.Lock()
		isDupe = h.fingerprints[normalized.Fingerprint]
		if !isDupe {
			h.fingerprints[normalized.Fingerprint] = true
		}
		h.mu.Unlock()
	}

	if isDupe {
		result.DuplicateItems++
		return
	}

	// Within-batch dedup
	h.mu.Lock()
	alreadySeen := h.fingerprints[normalized.Fingerprint]
	h.fingerprints[normalized.Fingerprint] = true
	h.mu.Unlock()

	if alreadySeen {
		result.DuplicateItems++
		return
	}

	catResult := categorizer.Categorize(normalized.MerchantName)
	normalized.Category = catResult.Category
	normalized.Confidence.Category = catResult.Confidence

	// Track confidence
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

	entityID := uuid.New().String()
	result.Transactions = append(result.Transactions, *normalized)
	result.Fingerprints = append(result.Fingerprints, FingerprintRecord{
		UserID:      userID,
		Fingerprint: normalized.Fingerprint,
		SourceType:  "gmail",
		EntityType:  "transaction",
		EntityID:    entityID,
	})

	log.Printf("[gmail] Transaction: %s ₹%.2f %s (cat=%s, conf=%.2f)",
		normalized.MerchantName, normalized.Amount,
		normalized.Date.Format("2006-01-02"),
		normalized.Category, normalized.Confidence.Overall,
	)

	result.NetNewItems++
}

func (h *GmailHandler) processBill(userID string, data *ExtractedFinancialData, result *GmailResult) {
	fp := normalizer.ComputeFingerprint(
		userID, data.Amount, data.BillerName,
		parseDateOrNow(data.BillDueDate), "bill",
	)

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
		DueDate:    data.BillDueDate,
		Status:     "upcoming",
		SourceType: "gmail",
	})
	result.Fingerprints = append(result.Fingerprints, FingerprintRecord{
		UserID:      userID,
		Fingerprint: fp,
		SourceType:  "gmail",
		EntityType:  "bill",
		EntityID:    billID,
	})

	log.Printf("[gmail] Bill: %s ₹%.2f due=%s (conf=%.2f)",
		data.BillerName, data.Amount, data.BillDueDate, data.Confidence,
	)

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

	// Insert bills
	for _, bill := range r.Bills {
		_, err := tx.Exec(ctx, `
			INSERT INTO bills (id, user_id, bill_type, biller_name, amount, currency,
				due_date, status, detection_source, created_at, updated_at)
			VALUES ($1, $2, 'other', $3, $4, $5, $6, $7, $8, now(), now())`,
			bill.ID, bill.UserID, bill.BillerName, bill.Amount, bill.Currency,
			bill.DueDate, bill.Status, bill.SourceType)
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

	// Update user_connections with new history_id and last_synced_at
	if r.ConnectionID != "" {
		_, err = tx.Exec(ctx, `
			UPDATE user_connections SET
				history_id = $2, last_synced_at = now(), last_sync_status = 'success', updated_at = now()
			WHERE id = $1`,
			r.ConnectionID, r.NewHistoryID)
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
		r.SyncSessionID, r.TransactionsFound+r.BillsFound, r.FailedItems,
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

// fetchFinancialEmails simulates fetching emails from Gmail API.
func fetchFinancialEmails(gmailAddress string, historyID string) []GmailEmail {
	return []GmailEmail{}
}

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
			BillerName: billerName, BillDueDate: dueDate,
			AccountLast4: accountLast4, Confidence: confidence,
		}
	}

	merchant := extractMerchant(content, email.From)
	confidence := computeEmailConfidence(amount, amountConf, merchant, "")
	return &ExtractedFinancialData{
		Type: "transaction", Merchant: merchant, Amount: amount, Date: date,
		AccountLast4: accountLast4, Confidence: confidence,
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
