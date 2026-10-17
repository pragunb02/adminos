//go:build integration

package handlers

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/adminos/adminos/workers/internal/tokencrypt"
)

// gmailConnection inserts a connected Gmail connection with the given stored tokens and history ID.
func gmailConnection(t *testing.T, ctx context.Context, pool *pgxpool.Pool, userID, access, refresh, historyID string, expires *time.Time) string {
	t.Helper()
	id := uuid.New().String()
	if _, err := pool.Exec(ctx, `
		INSERT INTO user_connections (id, user_id, source_type, status, gmail_address, access_token, refresh_token, token_expires_at, history_id)
		VALUES ($1, $2, 'gmail', 'connected', $3, NULLIF($4, ''), NULLIF($5, ''), $6, NULLIF($7, ''))`,
		id, userID, "u-"+id+"@gmail.com", access, refresh, expires, historyID); err != nil {
		t.Fatalf("create gmail connection: %v", err)
	}
	return id
}

func gmailSession(t *testing.T, ctx context.Context, pool *pgxpool.Pool, userID, connID string) string {
	t.Helper()
	id := uuid.New().String()
	if _, err := pool.Exec(ctx, `INSERT INTO sync_sessions (id, user_id, connection_id, sync_type) VALUES ($1, $2, $3, 'incremental')`, id, userID, connID); err != nil {
		t.Fatalf("create sync session: %v", err)
	}
	return id
}

// AOS-401: a whole sync against Postgres: encrypted tokens are read and refreshed tokens written
// encrypted; Gmail's real history ID, the event and the session outcome are stored.
func TestGmailSyncEndToEnd_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	defer cleanupTestUser(t, pool, userID)
	cipher := testCipher(t)
	connID := uuid.New().String()
	if _, err := pool.Exec(ctx, `
		INSERT INTO user_connections (id, user_id, source_type, status, gmail_address, access_token, refresh_token, history_id)
		VALUES ($1, $2, 'gmail', 'connected', $3, $4, $5, 'history_1760000000')`,
		connID, userID, "e2e-"+connID+"@gmail.com",
		seal(t, cipher, "old-access", connID, "access_token"), "legacy-plain-refresh"); err != nil {
		t.Fatalf("create connection: %v", err)
	}
	sessionID := gmailSession(t, ctx, pool, userID, connID)

	google := newFakeGoogle(t)
	google.profileHistoryID = "424242"
	google.listIDs = []string{"msg-e2e"}
	google.addMessage("msg-e2e", hdfc, "Transaction alert", alertDate, textPart("Rs 349.00 has been debited from your A/c XX1234 at ZOMATO"))
	h := NewGmailHandler(pool, google.client(), GmailConfig{Cipher: cipher})

	res, err := h.Execute(ctx, &GmailPayload{UserID: userID, ConnectionID: connID, SyncSessionID: sessionID})
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if err := h.Persist(ctx, res); err != nil {
		t.Fatalf("persist: %v", err)
	}

	var access, refresh, history, lastStatus, sessionStatus string
	var expires *time.Time
	var total int
	if err := pool.QueryRow(ctx, `SELECT access_token, refresh_token, history_id, last_sync_status::text, token_expires_at, total_synced FROM user_connections WHERE id = $1`, connID).
		Scan(&access, &refresh, &history, &lastStatus, &expires, &total); err != nil {
		t.Fatal(err)
	}
	if history != "424242" || lastStatus != "success" || expires == nil || total != 1 {
		t.Errorf("connection: history=%s status=%s expires=%v total=%d", history, lastStatus, expires, total)
	}
	for field, stored := range map[string]string{"access_token": access, "refresh_token": refresh} {
		plain, legacy, err := cipher.Decrypt(stored, tokencrypt.ConnectionContext(connID, field))
		if err != nil || legacy || strings.Contains(stored, plain) {
			t.Errorf("%s must be stored v2-encrypted, got %q", field, stored)
		}
	}
	if err := pool.QueryRow(ctx, `SELECT status::text FROM sync_sessions WHERE id = $1`, sessionID).Scan(&sessionStatus); err != nil || sessionStatus != "completed" {
		t.Errorf("session status %q (%v)", sessionStatus, err)
	}
	var amount string
	if err := pool.QueryRow(ctx, `SELECT amount::text FROM ingest_events WHERE user_id = $1 AND source = 'gmail' AND source_ref = 'msg-e2e'`, userID).Scan(&amount); err != nil || amount != "349.00" {
		t.Errorf("ingest event amount %q (%v)", amount, err)
	}
}

// AOS-401: the history ID only moves forward; a made-up one is replaced; "" keeps the stored one.
func TestGmailPersistHistoryIDOnlyMovesForward_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	defer cleanupTestUser(t, pool, userID)
	h := NewGmailHandler(pool, nil, GmailConfig{})

	cases := []struct{ stored, synced, want string }{
		{"500", "600", "600"},
		{"500", "400", "500"},          // an older sync finishing late doesn't rewind
		{"99", "100", "100"},           // numeric, not text, order
		{"history_1760000000", "7", "7"}, // the old made-up value is replaced
		{"", "8", "8"},
		{"900", "", "900"},
	}
	for _, c := range cases {
		connID := gmailConnection(t, ctx, pool, userID, "", "", c.stored, nil)
		sessionID := gmailSession(t, ctx, pool, userID, connID)
		if err := h.Persist(ctx, &GmailResult{SyncSessionID: sessionID, UserID: userID, ConnectionID: connID, NewHistoryID: c.synced}); err != nil {
			t.Fatalf("persist: %v", err)
		}
		var got *string
		if err := pool.QueryRow(ctx, `SELECT history_id FROM user_connections WHERE id = $1`, connID).Scan(&got); err != nil {
			t.Fatal(err)
		}
		if got == nil || *got != c.want {
			t.Errorf("stored %q + synced %q: want %q, got %v", c.stored, c.synced, c.want, got)
		}
	}
}

// AOS-401: a failed sync is recorded on the session and the connection, without content.
func TestGmailStoreRecordsFailuresAndRevocation_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	defer cleanupTestUser(t, pool, userID)
	store := &pgConnectionStore{db: pool}
	connID := gmailConnection(t, ctx, pool, userID, "v2:a", "v2:r", "77", nil)
	sessionID := gmailSession(t, ctx, pool, userID, connID)

	if err := store.RecordFailure(ctx, syncFailure{ConnectionID: connID, SyncSessionID: sessionID, Reason: "gmail temporarily unavailable"}); err != nil {
		t.Fatal(err)
	}
	var lastStatus, lastError, sessionStatus, reason string
	_ = pool.QueryRow(ctx, `SELECT last_sync_status::text, last_error FROM user_connections WHERE id = $1`, connID).Scan(&lastStatus, &lastError)
	_ = pool.QueryRow(ctx, `SELECT status::text, error_details->>'reason' FROM sync_sessions WHERE id = $1`, sessionID).Scan(&sessionStatus, &reason)
	if lastStatus != "failed" || lastError != "gmail temporarily unavailable" || sessionStatus != "failed" || reason != lastError {
		t.Errorf("connection %s/%s, session %s/%s", lastStatus, lastError, sessionStatus, reason)
	}

	if err := store.RecordFailure(ctx, syncFailure{ConnectionID: connID, Reason: "no session"}); err != nil {
		t.Errorf("a failure without a session still updates the connection: %v", err)
	}
	if err := store.MarkRevoked(ctx, connID, "gmail access revoked: reconnect Gmail"); err != nil {
		t.Fatal(err)
	}
	var status string
	_ = pool.QueryRow(ctx, `SELECT status::text FROM user_connections WHERE id = $1`, connID).Scan(&status)
	if status != "error" {
		t.Errorf("revoked connection status %q", status)
	}
}

// AOS-401: the store reads what the sync needs and writes only the tokens it is given.
func TestGmailStoreLoadsAndSavesTokens_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	defer cleanupTestUser(t, pool, userID)
	store := &pgConnectionStore{db: pool}
	exp := time.Date(2026, 10, 17, 7, 0, 0, 0, time.UTC)
	connID := gmailConnection(t, ctx, pool, userID, "v2:access", "v2:refresh", "1234", &exp)

	c, err := store.Load(ctx, connID)
	if err != nil || c.Status != "connected" || c.AccessToken != "v2:access" || c.RefreshToken != "v2:refresh" || c.HistoryID != "1234" || !c.ExpiresAt.Equal(exp) {
		t.Fatalf("load: %+v, %v", c, err)
	}
	newAccess, later := "v2:new-access", exp.Add(time.Hour)
	if err := store.SaveTokens(ctx, connID, tokenUpdate{AccessToken: &newAccess, ExpiresAt: &later}); err != nil {
		t.Fatal(err)
	}
	c, _ = store.Load(ctx, connID)
	if c.AccessToken != newAccess || c.RefreshToken != "v2:refresh" || !c.ExpiresAt.Equal(later) {
		t.Errorf("after save: %+v", c)
	}
	if _, err := store.Load(ctx, uuid.New().String()); err != errConnectionNotFound {
		t.Errorf("missing connection: %v", err)
	}
	smsID := createTestConnection(t, ctx, pool, userID)
	if _, err := store.Load(ctx, smsID); err != errConnectionNotFound {
		t.Errorf("an sms connection is not a gmail connection: %v", err)
	}
}
