package handlers

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
	DueDate    string
	Status     string
	SourceType string
}
