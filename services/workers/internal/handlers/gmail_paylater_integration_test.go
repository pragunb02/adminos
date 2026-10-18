//go:build integration

package handlers

import (
	"context"
	"testing"

	"github.com/google/uuid"
)

// AOS-909 on Postgres: a pay-later purchase keeps its method and provider; a bill already known from SMS isn't repeated.
func TestGmailPersistPayLater_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	connID := createTestConnection(t, ctx, pool, userID)
	sessionID := uuid.New().String()
	if _, err := pool.Exec(ctx, `INSERT INTO sync_sessions (id, user_id, connection_id, sync_type) VALUES ($1, $2, $3, 'manual')`, sessionID, userID, connID); err != nil {
		t.Fatalf("session: %v", err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO bills (user_id, bill_type, biller_name, amount, due_date, detection_source)
		VALUES ($1, 'pay_later', 'LazyPay', 1234, '2026-11-03', 'sms')`, userID); err != nil {
		t.Fatalf("sms bill: %v", err)
	}
	h := &GmailHandler{db: pool, fingerprints: map[string]bool{}}
	r := &GmailResult{SyncSessionID: sessionID, UserID: userID, ConnectionID: connID}
	date := "Sun, 18 Oct 2026 09:00:00 +0530"
	h.processPayLater(userID, GmailEmail{MessageID: "bill", From: "LazyPay <noreply@lazypay.in>", Subject: "Your LazyPay bill", Date: date,
		Body: "Total amount due: Rs 1,234.00\nDue date: 03-Nov-2026"}, r)
	h.processPayLater(userID, GmailEmail{MessageID: "buy", From: "Simpl <noreply@getsimpl.com>", Subject: "Transaction successful", Date: date,
		Body: "You spent Rs 450.00 at Swiggy using Simpl."}, r)
	if err := h.Persist(ctx, r); err != nil {
		t.Fatalf("persist: %v", err)
	}
	var bills int
	_ = pool.QueryRow(ctx, `SELECT count(*) FROM bills WHERE user_id = $1`, userID).Scan(&bills)
	if bills != 1 {
		t.Errorf("the email bill repeats the SMS bill: %d bills", bills)
	}
	var method, provider string
	if err := pool.QueryRow(ctx, `SELECT payment_method::text, metadata->>'payLater' FROM ingest_events WHERE user_id = $1 AND source_ref = 'buy'`, userID).
		Scan(&method, &provider); err != nil {
		t.Fatalf("event: %v", err)
	}
	if method != "pay_later" || provider != "Simpl" {
		t.Errorf("event: %s %s", method, provider)
	}
}
