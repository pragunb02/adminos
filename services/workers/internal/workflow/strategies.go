package workflow

import (
	"math/big"
	"time"
)

// --- Strategy interfaces for pluggable algorithms ---

// AnomalyRule evaluates a transaction and returns a result if the rule fires.
type AnomalyRule interface {
	Name() string
	BaseConfidence() float64
	Evaluate(txn TransactionData) *RuleResult
}

// RuleResult is returned when an anomaly rule fires.
type RuleResult struct {
	RuleName   string  `json:"rule_name"`
	Confidence float64 `json:"confidence"`
	Reason     string  `json:"reason"`
}

// CategorizationStrategy attempts to categorize a transaction.
// Returns nil if it can't determine a category.
type CategorizationStrategy interface {
	Priority() int // lower = tried first
	Categorize(merchantName string, amount *big.Float, method string) *CategoryResult
}

// CategoryResult is returned when categorization succeeds.
type CategoryResult struct {
	Category    string  `json:"category"`
	Subcategory string  `json:"subcategory,omitempty"`
	Confidence  float64 `json:"confidence"`
	Source      string  `json:"source"` // "rules", "ai", "user"
}

// WasteSignal evaluates a subscription for waste indicators.
type WasteSignal interface {
	Name() string
	Weight() float64
	Evaluate(sub SubscriptionData, signals GmailSignals) float64
}

// BankParser extracts transactions from a bank-specific PDF format.
type BankParser interface {
	BankCode() string
	CanParse(pdfHeader string) bool
	Parse(pdfContent []byte) ([]ExtractedTransaction, error)
}

// --- Shared data types used across strategies ---

// TransactionData is the input for anomaly rules and categorization.
type TransactionData struct {
	UserID       string     `json:"user_id"`
	Amount       float64    `json:"amount"`
	Currency     string     `json:"currency"`
	MerchantName string     `json:"merchant_name"`
	MerchantRaw  string     `json:"merchant_raw"`
	AccountLast4 string     `json:"account_last4"`
	PaymentMethod string    `json:"payment_method"`
	Category     string     `json:"category"`
	TransactedAt time.Time  `json:"transacted_at"`
}

// SubscriptionData is the input for waste scoring.
type SubscriptionData struct {
	ID              string    `json:"id"`
	UserID          string    `json:"user_id"`
	Name            string    `json:"name"`
	MerchantName    string    `json:"merchant_name"`
	Amount          float64   `json:"amount"`
	BillingCycle    string    `json:"billing_cycle"`
	FirstBilledDate time.Time `json:"first_billed_date"`
	LastBilledDate  time.Time `json:"last_billed_date"`
	Status          string    `json:"status"`
}

// GmailSignals contains email-based usage signals for a subscription.
type GmailSignals struct {
	LoginEmailCount  int `json:"login_email_count"`
	UsageEmailCount  int `json:"usage_email_count"`
	LastLoginEmail   *time.Time `json:"last_login_email,omitempty"`
	LastUsageEmail   *time.Time `json:"last_usage_email,omitempty"`
}

// ExtractedTransaction is the output of a bank PDF parser.
type ExtractedTransaction struct {
	Date        time.Time `json:"date"`
	Description string    `json:"description"`
	Amount      float64   `json:"amount"`
	Type        string    `json:"type"` // debit or credit
	Balance     float64   `json:"balance,omitempty"`
	Confidence  float64   `json:"confidence"`
}

// ConfidenceScores tracks per-field confidence for metadata.
type ConfidenceScores struct {
	Overall      float64 `json:"overall"`
	MerchantName float64 `json:"merchant_name"`
	Amount       float64 `json:"amount"`
	Date         float64 `json:"date"`
	Category     float64 `json:"category"`
	Account      float64 `json:"account"`
}
