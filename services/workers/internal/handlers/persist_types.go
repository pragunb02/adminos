package handlers

import "time"

// FingerprintRecord represents a fingerprint to be persisted.
type FingerprintRecord struct {
	UserID      string
	Fingerprint string
	SourceType  string
	EntityType  string
	EntityID    string
}

// BillRecord represents a bill to be persisted.
type BillRecord struct {
	ID         string
	UserID     string
	BillerName string
	Amount     float64
	Currency   string
	DueDate    string // ISO yyyy-mm-dd (normalizeDueDate); bills without one are skipped (AOS-503)
	Status     string
	SourceType string
	BillType   string  // bills.bill_type enum value, from the email's wording (detectBillType)
	AccountRef *string // card/account last 4 when the email shows it: lets payments match the bill (AOS-508)
}

// IngestEventRecord is a Gmail transaction for the common intake (ingest_events, D-21, AOS-205).
type IngestEventRecord struct {
	UserID      string
	SourceRef   string // Gmail message ID: re-syncing the same email is a duplicate
	OccurredAt  time.Time
	Direction   string // "debit" or "credit"
	Amount      string // exact decimal, 2 places
	MerchantRaw *string
	AccountHint *string
}
