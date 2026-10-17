//go:build integration

package handlers

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
)

// AOS-205: Gmail transactions land in ingest_events (pending) for the API pipeline, never in transactions.
func TestGmailPersistWritesIngestEvents_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	connID := createTestConnection(t, ctx, pool, userID)
	sessionID := uuid.New().String()
	if _, err := pool.Exec(ctx, `INSERT INTO sync_sessions (id, user_id, connection_id, sync_type) VALUES ($1, $2, $3, 'manual')`,
		sessionID, userID, connID); err != nil {
		t.Fatalf("create sync session: %v", err)
	}

	merchant, last4 := "ACME", "1234"
	at := time.Date(2026, 10, 16, 3, 42, 0, 0, time.UTC)
	result := func() *GmailResult {
		return &GmailResult{SyncSessionID: sessionID, UserID: userID, ConnectionID: connID, TotalEmails: 1, TransactionsFound: 1,
			Events: []IngestEventRecord{{UserID: userID, SourceRef: "msg-1", OccurredAt: at, Direction: "credit", Amount: "75000.00",
				MerchantRaw: &merchant, AccountHint: &last4}}}
	}
	h := NewGmailHandler(pool, nil, "", "")
	first, second := result(), result()
	if err := h.Persist(ctx, first); err != nil {
		t.Fatalf("persist: %v", err)
	}
	if err := h.Persist(ctx, second); err != nil {
		t.Fatalf("persist again: %v", err)
	}
	if first.NetNewItems != 1 || second.DuplicateItems != 1 {
		t.Errorf("want new=1 then duplicate=1, got %d / %d", first.NetNewItems, second.DuplicateItems)
	}

	var direction, status, amount string
	var paymentMethod *string
	var occurred time.Time
	if err := pool.QueryRow(ctx, `SELECT direction::text, status::text, amount::text, payment_method::text, occurred_at
		FROM ingest_events WHERE user_id = $1 AND source = 'gmail' AND source_ref = 'msg-1'`, userID).
		Scan(&direction, &status, &amount, &paymentMethod, &occurred); err != nil {
		t.Fatalf("query ingest event: %v", err)
	}
	if direction != "credit" || status != "pending" || amount != "75000.00" || paymentMethod != nil || !occurred.Equal(at) {
		t.Errorf("event: direction=%s status=%s amount=%s method=%v at=%v", direction, status, amount, paymentMethod, occurred)
	}
	var txns int
	_ = pool.QueryRow(ctx, `SELECT count(*) FROM transactions WHERE user_id = $1`, userID).Scan(&txns)
	if txns != 0 {
		t.Errorf("the worker must not write transactions directly; found %d", txns)
	}
}

// AOS-503: an email bill is stored with its ISO due date, its type and the card's last 4.
func TestGmailPersistWritesTypedBills_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	connID := createTestConnection(t, ctx, pool, userID)
	sessionID := uuid.New().String()
	if _, err := pool.Exec(ctx, `INSERT INTO sync_sessions (id, user_id, connection_id, sync_type) VALUES ($1, $2, $3, 'manual')`,
		sessionID, userID, connID); err != nil {
		t.Fatalf("create sync session: %v", err)
	}
	last4 := "4521"
	billID := uuid.New().String()
	result := &GmailResult{SyncSessionID: sessionID, UserID: userID, ConnectionID: connID, TotalEmails: 1, BillsFound: 1,
		Bills: []BillRecord{{ID: billID, UserID: userID, BillerName: "HDFC Bank Credit Card", Amount: 18450, Currency: "INR",
			DueDate: "2026-11-05", Status: "upcoming", SourceType: "gmail", BillType: "credit_card", AccountRef: &last4}}}
	if err := NewGmailHandler(pool, nil, "", "").Persist(ctx, result); err != nil {
		t.Fatalf("persist: %v", err)
	}
	var billType, due string
	var ref *string
	if err := pool.QueryRow(ctx, `SELECT bill_type::text, due_date::text, account_ref FROM bills WHERE id = $1`, billID).Scan(&billType, &due, &ref); err != nil {
		t.Fatalf("query bill: %v", err)
	}
	if billType != "credit_card" || due != "2026-11-05" || ref == nil || *ref != "4521" {
		t.Errorf("bill: type=%s due=%s ref=%v", billType, due, ref)
	}
}
