package normalizer

import (
	"testing"
	"time"
)

func TestNormalize_KnownMerchant(t *testing.T) {
	record := SmsRecord{Merchant: "ZOMATO", Amount: 450, Date: "2025-01-15", AccountLast4: "4521"}
	result, err := Normalize(record, "user-123")
	if err != nil {
		t.Fatalf("Normalize failed: %v", err)
	}

	if result.MerchantName != "Zomato" {
		t.Errorf("Expected 'Zomato', got '%s'", result.MerchantName)
	}
	if result.Amount != 450.0 {
		t.Errorf("Expected 450.0, got %f", result.Amount)
	}
	if result.Confidence.MerchantName != 0.95 {
		t.Errorf("Expected merchant confidence 0.95, got %f", result.Confidence.MerchantName)
	}
	if result.Confidence.Amount != 1.0 {
		t.Errorf("Expected amount confidence 1.0, got %f", result.Confidence.Amount)
	}
}

func TestNormalize_UnknownMerchant(t *testing.T) {
	record := SmsRecord{Merchant: "RANDOM SHOP XYZ", Amount: 200, Date: "2025-01-15", AccountLast4: "4521"}
	result, err := Normalize(record, "user-123")
	if err != nil {
		t.Fatalf("Normalize failed: %v", err)
	}

	if result.Confidence.MerchantName != 0.7 {
		t.Errorf("Expected merchant confidence 0.7 for unknown, got %f", result.Confidence.MerchantName)
	}
}

func TestComputeFingerprint_Deterministic(t *testing.T) {
	date := time.Date(2025, 1, 15, 0, 0, 0, 0, time.UTC)

	fp1 := ComputeFingerprint("user-123", 450.0, "Zomato", date, "4521")
	fp2 := ComputeFingerprint("user-123", 450.0, "Zomato", date, "4521")

	if fp1 != fp2 {
		t.Error("Same inputs should produce same fingerprint")
	}
	if len(fp1) != 64 {
		t.Errorf("Expected 64-char SHA256 hex, got %d chars", len(fp1))
	}
}

func TestComputeFingerprint_DifferentInputs(t *testing.T) {
	date := time.Date(2025, 1, 15, 0, 0, 0, 0, time.UTC)

	fp1 := ComputeFingerprint("user-123", 450.0, "Zomato", date, "4521")
	fp2 := ComputeFingerprint("user-123", 451.0, "Zomato", date, "4521")

	if fp1 == fp2 {
		t.Error("Different amounts should produce different fingerprints")
	}
}

func TestNormalize_DateFormats(t *testing.T) {
	tests := []struct {
		dateStr  string
		wantConf float64
	}{
		{"2025-01-15", 1.0},
		{"2025-01-15T12:30:00Z", 1.0},
		{"2025-01-15T12:30:00+05:30", 1.0},
		{"garbage", 0.3},
	}

	for _, tt := range tests {
		record := SmsRecord{Merchant: "TEST", Amount: 100, Date: tt.dateStr, AccountLast4: "1234"}
		result, err := Normalize(record, "user-123")
		if err != nil {
			t.Fatalf("Normalize failed for date '%s': %v", tt.dateStr, err)
		}
		if result.Confidence.Date != tt.wantConf {
			t.Errorf("Date '%s': expected confidence %f, got %f", tt.dateStr, tt.wantConf, result.Confidence.Date)
		}
	}
}

// --- Additional tests for normalization pipeline (Task 5.10) ---

func TestNormalize_MerchantTrimAndTitleCase(t *testing.T) {
	// Whitespace trimming and title-case for unknown merchants
	record := SmsRecord{Merchant: "  some random store  ", Amount: 100, Date: "2025-01-15", AccountLast4: "1234"}
	result, err := Normalize(record, "user-1")
	if err != nil {
		t.Fatalf("Normalize failed: %v", err)
	}
	if result.MerchantName != "Some Random Store" {
		t.Errorf("Expected 'Some Random Store', got '%s'", result.MerchantName)
	}
}

func TestNormalize_MerchantAliasResolution(t *testing.T) {
	aliases := map[string]string{
		"SWIGGY ORDER":    "Swiggy",
		"UBER TRIP":       "Uber",
		"OLA RIDE":        "Ola",
		"AMAZON PAY":      "Amazon",
		"FLIPKART ONLINE": "Flipkart",
		"NETFLIX.COM":     "Netflix",
		"SPOTIFY PREMIUM": "Spotify",
		"HOTSTAR VIP":     "Hotstar",
		"PAYTM WALLET":   "Paytm",
		"PHONEPE UPI":    "PhonePe",
		"GPAY TRANSFER":  "Google Pay",
		"GOOGLE PAY UPI": "Google Pay",
	}

	for input, expected := range aliases {
		record := SmsRecord{Merchant: input, Amount: 100, Date: "2025-01-15", AccountLast4: "1234"}
		result, err := Normalize(record, "user-1")
		if err != nil {
			t.Fatalf("Normalize failed for '%s': %v", input, err)
		}
		if result.MerchantName != expected {
			t.Errorf("Merchant '%s': expected '%s', got '%s'", input, expected, result.MerchantName)
		}
	}
}

func TestNormalize_ConfidenceScoring_Account(t *testing.T) {
	tests := []struct {
		accountLast4 string
		wantConf     float64
	}{
		{"4521", 0.95},  // 4-digit account
		{"45", 0.5},     // partial account
		{"", 0.0},       // no account
	}

	for _, tt := range tests {
		record := SmsRecord{Merchant: "TEST", Amount: 100, Date: "2025-01-15", AccountLast4: tt.accountLast4}
		result, err := Normalize(record, "user-1")
		if err != nil {
			t.Fatalf("Normalize failed: %v", err)
		}
		if result.Confidence.Account != tt.wantConf {
			t.Errorf("Account '%s': expected confidence %f, got %f",
				tt.accountLast4, tt.wantConf, result.Confidence.Account)
		}
	}
}

func TestNormalize_ConfidenceScoring_Amount(t *testing.T) {
	tests := []struct {
		amount   float64
		wantConf float64
	}{
		{450.0, 1.0},   // positive amount
		{0.01, 1.0},    // small positive
		{0.0, 0.3},     // zero
		{-100.0, 0.3},  // negative
	}

	for _, tt := range tests {
		record := SmsRecord{Merchant: "TEST", Amount: tt.amount, Date: "2025-01-15", AccountLast4: "1234"}
		result, err := Normalize(record, "user-1")
		if err != nil {
			t.Fatalf("Normalize failed: %v", err)
		}
		if result.Confidence.Amount != tt.wantConf {
			t.Errorf("Amount %.2f: expected confidence %f, got %f",
				tt.amount, tt.wantConf, result.Confidence.Amount)
		}
	}
}

func TestNormalize_OverallConfidence(t *testing.T) {
	// Known merchant + valid amount + valid date + 4-digit account = high overall
	record := SmsRecord{Merchant: "ZOMATO", Amount: 450, Date: "2025-01-15", AccountLast4: "4521"}
	result, err := Normalize(record, "user-1")
	if err != nil {
		t.Fatalf("Normalize failed: %v", err)
	}

	// Overall = (0.95 + 1.0 + 1.0 + 0.95) / 4 = 0.975 → rounded to 0.97 or 0.98
	if result.Confidence.Overall < 0.9 {
		t.Errorf("Expected high overall confidence for known merchant, got %f", result.Confidence.Overall)
	}

	// Unknown merchant + garbage date + no account = low overall
	record2 := SmsRecord{Merchant: "UNKNOWN", Amount: 100, Date: "garbage", AccountLast4: ""}
	result2, err := Normalize(record2, "user-1")
	if err != nil {
		t.Fatalf("Normalize failed: %v", err)
	}

	if result2.Confidence.Overall > 0.6 {
		t.Errorf("Expected low overall confidence for poor data, got %f", result2.Confidence.Overall)
	}
}

func TestNormalize_AmountRounding(t *testing.T) {
	record := SmsRecord{Merchant: "TEST", Amount: 99.999, Date: "2025-01-15", AccountLast4: "1234"}
	result, err := Normalize(record, "user-1")
	if err != nil {
		t.Fatalf("Normalize failed: %v", err)
	}
	if result.Amount != 100.0 {
		t.Errorf("Expected 100.0 (rounded), got %f", result.Amount)
	}
}

func TestNormalize_SourceType(t *testing.T) {
	record := SmsRecord{Merchant: "TEST", Amount: 100, Date: "2025-01-15", AccountLast4: "1234"}
	result, err := Normalize(record, "user-1")
	if err != nil {
		t.Fatalf("Normalize failed: %v", err)
	}
	if result.SourceType != "sms" {
		t.Errorf("Expected source_type 'sms', got '%s'", result.SourceType)
	}
}
