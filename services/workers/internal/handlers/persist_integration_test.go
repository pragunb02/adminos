//go:build integration

package handlers

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

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
