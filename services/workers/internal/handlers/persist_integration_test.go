//go:build integration

package handlers

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adminos/adminos/workers/internal/normalizer"
	"github.com/adminos/adminos/workers/internal/workflow"
)

// testPool creates a pgxpool connected to TEST_DATABASE_URL.
// Returns the pool and a cleanup function that closes it.
func testPool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dbURL := os.Getenv("TEST_DATABASE_URL")
	if dbURL == "" {
		t.Skip("TEST_DATABASE_URL not set — skipping integration test")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	pool, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		t.Fatalf("failed to connect to database: %v", err)
	}

	// Verify connectivity
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		t.Fatalf("failed to ping database: %v", err)
	}

	return pool
}

// createTestUser inserts a minimal user row and returns the user ID.
func createTestUser(t *testing.T, ctx context.Context, pool *pgxpool.Pool) string {
	t.Helper()
	userID := uuid.New().String()
	_, err := pool.Exec(ctx, `
		INSERT INTO users (id, email, google_id, created_at, updated_at)
		VALUES ($1, $2, $3, now(), now())`,
		userID, "test-"+userID+"@example.com", "google-"+userID)
	if err != nil {
		t.Fatalf("failed to create test user: %v", err)
	}
	return userID
}

// createTestConnection inserts a user_connection row and returns the connection ID.
func createTestConnection(t *testing.T, ctx context.Context, pool *pgxpool.Pool, userID string) string {
	t.Helper()
	connID := uuid.New().String()
	_, err := pool.Exec(ctx, `
		INSERT INTO user_connections (id, user_id, source_type, status, created_at, updated_at)
		VALUES ($1, $2, 'sms', 'connected', now(), now())`,
		connID, userID)
	if err != nil {
		t.Fatalf("failed to create test connection: %v", err)
	}
	return connID
}

// createTestSyncSession inserts a sync_session row and returns the session ID.
func createTestSyncSession(t *testing.T, ctx context.Context, pool *pgxpool.Pool, userID, connID string) string {
	t.Helper()
	sessionID := uuid.New().String()
	_, err := pool.Exec(ctx, `
		INSERT INTO sync_sessions (id, user_id, connection_id, sync_type, status, created_at, updated_at)
		VALUES ($1, $2, $3, 'manual', 'in_progress', now(), now())`,
		sessionID, userID, connID)
	if err != nil {
		t.Fatalf("failed to create test sync session: %v", err)
	}
	return sessionID
}

// cleanupTestUser deletes a user and all cascading data.
func cleanupTestUser(t *testing.T, pool *pgxpool.Pool, userID string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// CASCADE on users will clean up connections, sync_sessions, transactions, fingerprints, etc.
	_, _ = pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, userID)
}

// ---------------------------------------------------------------------------
// Task 6.8: Handler Persist and sync session update integration test
// **Validates: Requirements 4.2, 4.5**
// ---------------------------------------------------------------------------

func TestSmsHandlerPersist_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()

	ctx := context.Background()

	// Setup prerequisite data
	userID := createTestUser(t, ctx, pool)
	t.Cleanup(func() { cleanupTestUser(t, pool, userID) })

	connID := createTestConnection(t, ctx, pool, userID)
	sessionID := createTestSyncSession(t, ctx, pool, userID, connID)

	// Build an SmsResult with transactions and fingerprints
	txnDate := time.Date(2025, 1, 15, 10, 0, 0, 0, time.UTC)
	entityID1 := uuid.New().String()
	entityID2 := uuid.New().String()

	result := &SmsResult{
		SyncSessionID:  sessionID,
		UserID:         userID,
		TotalItems:     3,
		ProcessedItems: 3,
		DuplicateItems: 1,
		NetNewItems:    2,
		FailedItems:    0,
		HighConf:       1,
		MedConf:        1,
		LowConf:        0,
		NeedsReview:    0,
		AvgConfidence:  0.85,
		Transactions: []normalizer.NormalizedTransaction{
			{
				UserID:        userID,
				MerchantName:  "Zomato",
				MerchantRaw:   "ZOMATO ORDER",
				Amount:        450.00,
				Date:          txnDate,
				AccountLast4:  "4521",
				PaymentMethod: "upi",
				SourceType:    "sms",
				Category:      "food",
				Confidence: workflow.ConfidenceScores{
					Overall: 0.90,
				},
			},
			{
				UserID:        userID,
				MerchantName:  "Uber",
				MerchantRaw:   "UBER TRIP",
				Amount:        120.00,
				Date:          txnDate,
				AccountLast4:  "4521",
				PaymentMethod: "upi",
				SourceType:    "sms",
				Category:      "transport",
				Confidence: workflow.ConfidenceScores{
					Overall: 0.80,
				},
			},
		},
		Fingerprints: []FingerprintRecord{
			{UserID: userID, Fingerprint: "fp-persist-test-1-" + entityID1, SourceType: "sms", EntityType: "transaction", EntityID: entityID1},
			{UserID: userID, Fingerprint: "fp-persist-test-2-" + entityID2, SourceType: "sms", EntityType: "transaction", EntityID: entityID2},
		},
	}

	// Create handler with real DB pool and call Persist
	handler := NewSmsHandler(pool)
	err := handler.Persist(ctx, result)
	if err != nil {
		t.Fatalf("Persist failed: %v", err)
	}

	// Verify transactions were inserted
	var txnCount int
	err = pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM transactions WHERE user_id = $1`, userID).Scan(&txnCount)
	if err != nil {
		t.Fatalf("failed to count transactions: %v", err)
	}
	if txnCount != 2 {
		t.Errorf("expected 2 transactions, got %d", txnCount)
	}

	// Verify fingerprints were inserted
	var fpCount int
	err = pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM ingestion_fingerprints WHERE user_id = $1`, userID).Scan(&fpCount)
	if err != nil {
		t.Fatalf("failed to count fingerprints: %v", err)
	}
	if fpCount != 2 {
		t.Errorf("expected 2 fingerprints, got %d", fpCount)
	}

	// Verify sync session was updated with correct counts
	var status string
	var processedItems, failedItems, duplicateItems, netNewItems int
	err = pool.QueryRow(ctx,
		`SELECT status, processed_items, failed_items, duplicate_items, net_new_items
		 FROM sync_sessions WHERE id = $1`, sessionID).
		Scan(&status, &processedItems, &failedItems, &duplicateItems, &netNewItems)
	if err != nil {
		t.Fatalf("failed to query sync session: %v", err)
	}
	if status != "completed" {
		t.Errorf("expected sync session status 'completed', got '%s'", status)
	}
	if processedItems != 3 {
		t.Errorf("expected processed_items=3, got %d", processedItems)
	}
	if duplicateItems != 1 {
		t.Errorf("expected duplicate_items=1, got %d", duplicateItems)
	}
	if netNewItems != 2 {
		t.Errorf("expected net_new_items=2, got %d", netNewItems)
	}

	// Verify extraction quality row was inserted
	var eqCount int
	var totalRecords, highConf, medConf int
	err = pool.QueryRow(ctx,
		`SELECT total_records, high_confidence, medium_confidence
		 FROM extraction_quality WHERE sync_session_id = $1`, sessionID).
		Scan(&totalRecords, &highConf, &medConf)
	if err != nil {
		t.Fatalf("failed to query extraction_quality: %v", err)
	}
	_ = pool.QueryRow(ctx,
		`SELECT COUNT(*) FROM extraction_quality WHERE sync_session_id = $1`, sessionID).Scan(&eqCount)
	if eqCount != 1 {
		t.Errorf("expected 1 extraction_quality row, got %d", eqCount)
	}
	if totalRecords != 3 {
		t.Errorf("expected total_records=3, got %d", totalRecords)
	}
	if highConf != 1 {
		t.Errorf("expected high_confidence=1, got %d", highConf)
	}
	if medConf != 1 {
		t.Errorf("expected medium_confidence=1, got %d", medConf)
	}
}

// ---------------------------------------------------------------------------
// Task 6.9: Fingerprint deduplication integration test
// **Validates: Requirements 4.7**
// ---------------------------------------------------------------------------

func TestIsDuplicate_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()

	ctx := context.Background()

	userID := createTestUser(t, ctx, pool)
	t.Cleanup(func() { cleanupTestUser(t, pool, userID) })

	// Insert a known fingerprint directly
	entityID := uuid.New().String()
	_, err := pool.Exec(ctx, `
		INSERT INTO ingestion_fingerprints (user_id, fingerprint, source_type, entity_type, entity_id)
		VALUES ($1, $2, 'sms', 'transaction', $3)`,
		userID, "known-fingerprint-abc123", entityID)
	if err != nil {
		t.Fatalf("failed to insert test fingerprint: %v", err)
	}

	handler := NewSmsHandler(pool)

	// Test 1: existing fingerprint should be detected as duplicate
	isDupe, err := handler.isDuplicate(ctx, userID, "known-fingerprint-abc123")
	if err != nil {
		t.Fatalf("isDuplicate returned error: %v", err)
	}
	if !isDupe {
		t.Error("expected isDuplicate=true for existing fingerprint, got false")
	}

	// Test 2: new fingerprint should NOT be detected as duplicate
	isDupe, err = handler.isDuplicate(ctx, userID, "brand-new-fingerprint-xyz789")
	if err != nil {
		t.Fatalf("isDuplicate returned error: %v", err)
	}
	if isDupe {
		t.Error("expected isDuplicate=false for new fingerprint, got true")
	}

	// Test 3: same fingerprint for a different user should NOT be duplicate
	otherUserID := createTestUser(t, ctx, pool)
	t.Cleanup(func() { cleanupTestUser(t, pool, otherUserID) })

	isDupe, err = handler.isDuplicate(ctx, otherUserID, "known-fingerprint-abc123")
	if err != nil {
		t.Fatalf("isDuplicate returned error: %v", err)
	}
	if isDupe {
		t.Error("expected isDuplicate=false for different user's fingerprint, got true")
	}
}

// ---------------------------------------------------------------------------
// Task 8.4: Briefing metadata persistence integration test
// **Validates: Requirements 6.11**
// ---------------------------------------------------------------------------

func TestBriefingHandlerPersist_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()

	ctx := context.Background()

	userID := createTestUser(t, ctx, pool)
	t.Cleanup(func() { cleanupTestUser(t, pool, userID) })

	// Build a BriefingResult with metadata
	result := &BriefingResult{
		UserID:        userID,
		BriefingID:    "brief_test_" + uuid.New().String(),
		Content:       "Your weekly briefing: you spent ₹5000 this week.",
		PeriodStart:   "2025-01-06",
		PeriodEnd:     "2025-01-12",
		ModelUsed:     "claude-sonnet-4-20250514",
		PromptVersion: "v1.0",
		TokensUsed:    1234,
		GenerationMs:  450,
		Data: BriefingData{
			TotalSpent:           5000.00,
			TotalIncome:          25000.00,
			TopCategories:        []CategorySpending{},
			UpcomingBills:        []BillSummary{},
			FlaggedSubscriptions: []SubSummary{},
			AnomaliesDetected:    0,
		},
	}

	// Create handler with real DB pool (no Claude client needed for Persist)
	handler := NewBriefingHandler(pool, nil)
	err := handler.Persist(ctx, result)
	if err != nil {
		t.Fatalf("BriefingHandler.Persist failed: %v", err)
	}

	// Verify the briefing row was inserted with correct metadata
	var modelUsed, promptVersion string
	var tokensUsed, generationMs int
	var content string
	var totalSpent float64

	err = pool.QueryRow(ctx, `
		SELECT model_used, prompt_version, tokens_used, generation_ms, content, total_spent
		FROM briefings WHERE user_id = $1 AND period_start = $2`,
		userID, "2025-01-06").
		Scan(&modelUsed, &promptVersion, &tokensUsed, &generationMs, &content, &totalSpent)
	if err != nil {
		t.Fatalf("failed to query briefing: %v", err)
	}

	if modelUsed != "claude-sonnet-4-20250514" {
		t.Errorf("expected model_used='claude-sonnet-4-20250514', got '%s'", modelUsed)
	}
	if promptVersion != "v1.0" {
		t.Errorf("expected prompt_version='v1.0', got '%s'", promptVersion)
	}
	if tokensUsed != 1234 {
		t.Errorf("expected tokens_used=1234, got %d", tokensUsed)
	}
	if generationMs != 450 {
		t.Errorf("expected generation_ms=450, got %d", generationMs)
	}
	if content != "Your weekly briefing: you spent ₹5000 this week." {
		t.Errorf("unexpected content: %s", content)
	}
	if totalSpent != 5000.00 {
		t.Errorf("expected total_spent=5000.00, got %.2f", totalSpent)
	}
}
