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
