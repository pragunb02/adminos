package normalizer

import (
	"crypto/sha256"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/adminos/adminos/workers/internal/workflow"
)

// Normalize takes a raw SMS record and produces a normalized transaction
// with confidence scores for each field.
func Normalize(record SmsRecord, userID string) (*NormalizedTransaction, error) {
	merchant := normalizeMerchant(record.Merchant)
	amount, amountConf := normalizeAmount(record.Amount)
	txnDate, dateConf := normalizeDate(record.Date)
	accountConf := normalizeAccount(record.AccountLast4)
	merchantConf := merchantConfidence(merchant, record.Merchant)

	overall := (merchantConf + amountConf + dateConf + accountConf) / 4.0

	return &NormalizedTransaction{
		UserID:        userID,
		MerchantName:  merchant,
		MerchantRaw:   record.Merchant,
		Amount:        amount,
		Date:          txnDate,
		AccountLast4:  record.AccountLast4,
		PaymentMethod: record.PaymentMethod,
		SourceType:    "sms",
		Confidence: workflow.ConfidenceScores{
			Overall:      math.Round(overall*100) / 100,
			MerchantName: merchantConf,
			Amount:       amountConf,
			Date:         dateConf,
			Category:     0.0, // set after categorization
			Account:      accountConf,
		},
	}, nil
}

// ComputeFingerprint generates a SHA256 deduplication hash.
// Uses hour-level granularity to allow same-day same-merchant transactions
// at different times while still deduplicating true duplicates.
func ComputeFingerprint(userID string, amount float64, merchant string, date time.Time, accountLast4 string) string {
	input := fmt.Sprintf("%s|%.2f|%s|%s|%s",
		userID,
		amount,
		strings.ToLower(strings.TrimSpace(merchant)),
		date.Format("2006-01-02T15"), // hour-level granularity
		accountLast4,
	)
	hash := sha256.Sum256([]byte(input))
	return fmt.Sprintf("%x", hash)
}

// normalizeMerchant cleans up merchant names.
func normalizeMerchant(raw string) string {
	name := strings.TrimSpace(raw)
	name = strings.ToUpper(name)

	// Common alias replacements
	aliases := map[string]string{
		"ZOMATO":       "Zomato",
		"SWIGGY":       "Swiggy",
		"UBER":         "Uber",
		"OLA":          "Ola",
		"AMAZON":       "Amazon",
		"FLIPKART":     "Flipkart",
		"NETFLIX":      "Netflix",
		"SPOTIFY":      "Spotify",
		"HOTSTAR":      "Hotstar",
		"PAYTM":        "Paytm",
		"PHONEPE":      "PhonePe",
		"GPAY":         "Google Pay",
		"GOOGLE PAY":   "Google Pay",
	}

	for key, val_ := range aliases {
		if strings.Contains(name, key) {
			return val_
		}
	}

	// Title case fallback (manual implementation — strings.Title is deprecated since Go 1.18)
	words := strings.Fields(strings.ToLower(strings.TrimSpace(raw)))
	for i, w := range words {
		if len(w) > 0 {
			words[i] = strings.ToUpper(w[:1]) + w[1:]
		}
	}
	return strings.Join(words, " ")
}

func normalizeAmount(amount float64) (float64, float64) {
	if amount <= 0 {
		return amount, 0.3
	}
	// Round to 2 decimal places
	rounded := math.Round(amount*100) / 100
	return rounded, 1.0 // structured JSON always has exact amount
}

func normalizeDate(dateStr string) (time.Time, float64) {
	// Try common formats
	formats := []string{
		time.RFC3339,
		"2006-01-02T15:04:05-07:00",
		"2006-01-02T15:04:05Z",
		"2006-01-02T15:04:05",
		"2006-01-02",
		"02/01/2006",
		"02-01-2006",
	}

	for _, format := range formats {
		if t, err := time.Parse(format, dateStr); err == nil {
			return t, 1.0
		}
	}

	// Fallback: return now with low confidence
	return time.Now(), 0.3
}

func normalizeAccount(last4 string) float64 {
	if len(last4) == 4 {
		return 0.95
	}
	if len(last4) > 0 {
		return 0.5
	}
	return 0.0
}

func merchantConfidence(normalized, raw string) float64 {
	// Known merchants get high confidence
	known := []string{"Zomato", "Swiggy", "Uber", "Ola", "Amazon", "Flipkart",
		"Netflix", "Spotify", "Hotstar", "Paytm", "PhonePe", "Google Pay"}
	for _, k := range known {
		if normalized == k {
			return 0.95
		}
	}
	// Unknown but parseable
	if len(normalized) > 0 {
		return 0.7
	}
	return 0.3
}

// SmsRecord is the input from the API.
type SmsRecord struct {
	Merchant      string  `json:"merchant"`
	Amount        float64 `json:"amount"`
	Date          string  `json:"date"`
	AccountLast4  string  `json:"account_last4"`
	PaymentMethod string  `json:"payment_method,omitempty"`
}

// NormalizedTransaction is the output of normalization.
type NormalizedTransaction struct {
	UserID        string                   `json:"user_id"`
	MerchantName  string                   `json:"merchant_name"`
	MerchantRaw   string                   `json:"merchant_raw"`
	Amount        float64                  `json:"amount"`
	Date          time.Time                `json:"date"`
	AccountLast4  string                   `json:"account_last4"`
	PaymentMethod string                   `json:"payment_method"`
	SourceType    string                   `json:"source_type"`
	Fingerprint   string                   `json:"fingerprint"`
	Category      string                   `json:"category"`
	Confidence    workflow.ConfidenceScores `json:"confidence"`
	IsDuplicate   bool                     `json:"is_duplicate"`
}
