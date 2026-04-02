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

	"github.com/adminos/adminos/workers/internal/categorizer"
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
	TotalEmails      int    `json:"total_emails"`
	TransactionsFound int   `json:"transactions_found"`
	BillsFound       int    `json:"bills_found"`
	DuplicateItems   int    `json:"duplicate_items"`
	NetNewItems      int    `json:"net_new_items"`
	FailedItems      int    `json:"failed_items"`
	NewHistoryID     string `json:"new_history_id,omitempty"`
}

// GmailHandler processes Gmail ingestion jobs.
// Implements the JobHandler interface from the workflow package.
//
// Concurrency: When Gmail API is integrated, emails should be processed
// in parallel using errgroup.SetLimit(10) since each email parse involves
// I/O (Gmail API fetch) + CPU (regex parsing). The fingerprints map is
// mutex-protected for goroutine safety.
type GmailHandler struct {
	mu           sync.Mutex
	fingerprints map[string]bool
}

func NewGmailHandler() *GmailHandler {
	return &GmailHandler{
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

	// In production, this would call Gmail API: messages.list with financial filters
	// For MVP, we process emails passed via the payload or fetched from Gmail API
	// The emails would be fetched with query: "from:bank OR subject:payment OR subject:bill"
	// TODO: When Gmail API is integrated, process emails in parallel using errgroup:
	//   g, ctx := errgroup.WithContext(ctx)
	//   g.SetLimit(10)  // bounded concurrency
	//   for _, email := range emails { g.Go(func() error { ... }) }
	//   g.Wait()
	emails := fetchFinancialEmails(p.GmailAddress, p.HistoryID)

	result := &GmailResult{
		SyncSessionID: p.SyncSessionID,
		TotalEmails:   len(emails),
	}

	for _, email := range emails {
		select {
		case <-ctx.Done():
			return result, ctx.Err()
		default:
		}

		// Parse email for financial patterns
		extracted := parseFinancialEmail(email)
		if extracted == nil {
			continue
		}

		if extracted.Type == "bill" {
			result.BillsFound++
			h.processBill(p.UserID, extracted, result)
		} else {
			result.TransactionsFound++
			h.processTransaction(p.UserID, extracted, result)
		}
	}

	result.NewHistoryID = fmt.Sprintf("history_%d", time.Now().Unix())
	return result, nil
}

func (h *GmailHandler) processTransaction(userID string, data *ExtractedFinancialData, result *GmailResult) {
	// Step 1: Normalize using the same pipeline as SMS
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

	// Override source type
	normalized.SourceType = "gmail"

	// Adjust confidence based on email extraction quality
	normalized.Confidence.Overall = math.Round(
		(normalized.Confidence.Overall*0.7+data.Confidence*0.3)*100) / 100

	// Step 2: Compute fingerprint
	normalized.Fingerprint = normalizer.ComputeFingerprint(
		userID,
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
		return
	}

	// Step 4: Categorize
	catResult := categorizer.Categorize(normalized.MerchantName)
	normalized.Category = catResult.Category
	normalized.Confidence.Category = catResult.Confidence

	// Step 5: Would persist to transactions table
	log.Printf("[gmail] Transaction: %s ₹%.2f %s (cat=%s, conf=%.2f)",
		normalized.MerchantName,
		normalized.Amount,
		normalized.Date.Format("2006-01-02"),
		normalized.Category,
		normalized.Confidence.Overall,
	)

	result.NetNewItems++
}

func (h *GmailHandler) processBill(userID string, data *ExtractedFinancialData, result *GmailResult) {
	// Compute fingerprint for bill deduplication
	fp := normalizer.ComputeFingerprint(
		userID,
		data.Amount,
		data.BillerName,
		parseDateOrNow(data.BillDueDate),
		"bill",
	)

	h.mu.Lock()
	billDupe := h.fingerprints[fp]
	if !billDupe {
		h.fingerprints[fp] = true
	}
	h.mu.Unlock()

	if billDupe {
		result.DuplicateItems++
		return
	}

	// Would insert into bills table with status "upcoming"
	log.Printf("[gmail] Bill: %s ₹%.2f due=%s (conf=%.2f)",
		data.BillerName,
		data.Amount,
		data.BillDueDate,
		data.Confidence,
	)

	result.NetNewItems++
}

func (h *GmailHandler) Persist(ctx context.Context, result any) error {
	r := result.(*GmailResult)
	// TODO: Update sync_session in PostgreSQL with final counts
	// TODO: Update user_connection.history_id and last_synced_at
	log.Printf("[gmail] Session %s complete: emails=%d, txns=%d, bills=%d, dupes=%d, new=%d",
		r.SyncSessionID, r.TotalEmails, r.TransactionsFound, r.BillsFound,
		r.DuplicateItems, r.NetNewItems)
	return nil
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
// In production, this calls Gmail API with financial filters.
func fetchFinancialEmails(gmailAddress string, historyID string) []GmailEmail {
	// TODO: Implement actual Gmail API call
	// Gmail API query: "from:bank OR subject:payment OR subject:bill OR subject:transaction"
	// Use history_id for incremental sync
	return []GmailEmail{}
}

// parseFinancialEmail extracts financial data from an email.
func parseFinancialEmail(email GmailEmail) *ExtractedFinancialData {
	content := email.Subject + " " + email.Body
	contentLower := strings.ToLower(content)

	// Determine if this is a bill or transaction
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

	// Extract amount
	amount, amountConf := extractAmount(content)
	if amount <= 0 {
		return nil
	}

	// Extract date
	date := extractDate(content)
	if date == "" {
		date = email.Date
	}

	// Extract account
	accountLast4 := extractAccountLast4(content)

	if isBill {
		dueDate := extractDueDate(content)
		billerName := extractMerchant(content, email.From)

		confidence := computeEmailConfidence(amount, amountConf, billerName, dueDate)

		return &ExtractedFinancialData{
			Type:         "bill",
			Merchant:     billerName,
			Amount:       amount,
			Date:         date,
			BillerName:   billerName,
			BillDueDate:  dueDate,
			AccountLast4: accountLast4,
			Confidence:   confidence,
		}
	}

	merchant := extractMerchant(content, email.From)
	confidence := computeEmailConfidence(amount, amountConf, merchant, "")

	return &ExtractedFinancialData{
		Type:         "transaction",
		Merchant:     merchant,
		Amount:       amount,
		Date:         date,
		AccountLast4: accountLast4,
		Confidence:   confidence,
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
	// Try regex patterns first
	for _, pattern := range merchantPatterns {
		matches := pattern.FindStringSubmatch(content)
		if len(matches) >= 2 {
			merchant := strings.TrimSpace(matches[1])
			if len(merchant) > 2 {
				return merchant
			}
		}
	}

	// Fallback: extract from sender name
	if from != "" {
		// Parse "Name <email>" format
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
	formats := []string{
		"2006-01-02",
		"02/01/2006",
		"02-01-2006",
		time.RFC3339,
	}
	for _, f := range formats {
		if t, err := time.Parse(f, dateStr); err == nil {
			return t
		}
	}
	return time.Now()
}
