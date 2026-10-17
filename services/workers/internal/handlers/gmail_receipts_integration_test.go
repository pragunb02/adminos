//go:build integration

package handlers

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/adminos/adminos/workers/internal/receipts"
)

// AOS-903 on Postgres: every email about one order is one row; fees and refunds are never counted twice.
func TestGmailPersistMergesEveryEmailAboutAnOrder_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	connID := createTestConnection(t, ctx, pool, userID)
	h := NewGmailHandler(pool, nil, GmailConfig{})
	persist := func(recs ...ReceiptRecord) *GmailResult {
		t.Helper()
		sessionID := uuid.New().String()
		if _, err := pool.Exec(ctx, `INSERT INTO sync_sessions (id, user_id, connection_id, sync_type) VALUES ($1, $2, $3, 'manual')`,
			sessionID, userID, connID); err != nil {
			t.Fatalf("create sync session: %v", err)
		}
		r := &GmailResult{SyncSessionID: sessionID, UserID: userID, ConnectionID: connID, Receipts: recs, ReceiptsFound: len(recs)}
		if err := h.Persist(ctx, r); err != nil {
			t.Fatalf("persist: %v", err)
		}
		return r
	}
	ordered := time.Date(2026, 10, 16, 18, 18, 0, 0, time.UTC)
	base := receipts.Receipt{App: "Zomato", Vertical: receipts.FoodDelivery, OrderID: "6655443322"}
	cancelFirst := base
	cancelFirst.Kind, cancelFirst.OrderedAt, cancelFirst.Payment = receipts.KindRefund, ordered.Add(10*time.Hour), receipts.PayUnknown
	cancelFirst.RefundAmount = 12000
	order := base
	order.Kind, order.OrderedAt, order.Total, order.ItemCount, order.Payment = receipts.KindOrder, ordered, 74550, 2, receipts.PayWallet
	order.Fees = []receipts.Fee{{Kind: receipts.FeePlatform, Amount: 1000}, {Kind: receipts.FeeTip, Amount: 3000}}
	delivered := order
	delivered.OrderedAt, delivered.Total, delivered.ItemCount = ordered.Add(40*time.Minute), 99900, 5 // never overwrites
	delivered.Fees = []receipts.Fee{{Kind: receipts.FeePlatform, Amount: 9900}, {Kind: receipts.FeePackaging, Amount: 3000}}

	first := persist(newReceiptRecord(userID, "refund-1", cancelFirst))
	second := persist(newReceiptRecord(userID, "order", order), newReceiptRecord(userID, "delivered", delivered))
	persist(newReceiptRecord(userID, "refund-2", cancelFirst)) // "credited" after "initiated"

	if first.NetNewItems != 1 || second.NetNewItems != 0 || second.DuplicateItems != 2 {
		t.Errorf("want 1 new then 2 merged, got %d / %d,%d", first.NetNewItems, second.NetNewItems, second.DuplicateItems)
	}
	var rows int
	var total, mode, status, state string
	var items int
	var at time.Time
	if err := pool.QueryRow(ctx, `SELECT count(*) OVER (), order_total::text, item_count, payment_mode, status, match_state, ordered_at
		FROM order_receipts WHERE user_id = $1`, userID).Scan(&rows, &total, &items, &mode, &status, &state, &at); err != nil {
		t.Fatalf("query: %v", err)
	}
	if rows != 1 || total != "745.50" || items != 2 || mode != "wallet" || status != "placed" || state != "unmatched" || !at.Equal(ordered) {
		t.Errorf("row: rows=%d total=%s items=%d mode=%s status=%s state=%s at=%v", rows, total, items, mode, status, state, at)
	}
	var fees, refunds int
	var feeSum, refundSum string
	_ = pool.QueryRow(ctx, `SELECT count(*), sum(f.amount)::text FROM order_receipt_fees f JOIN order_receipts r ON r.id = f.receipt_id WHERE r.user_id = $1`, userID).Scan(&fees, &feeSum)
	_ = pool.QueryRow(ctx, `SELECT count(*), sum(x.amount)::text FROM order_receipt_refunds x JOIN order_receipts r ON r.id = x.receipt_id WHERE r.user_id = $1`, userID).Scan(&refunds, &refundSum)
	if fees != 3 || feeSum != "70.00" {
		t.Errorf("fees: first amount per kind wins, blanks filled: %d rows, %s", fees, feeSum)
	}
	if refunds != 1 || refundSum != "120.00" {
		t.Errorf("one refund: %d rows, %s", refunds, refundSum)
	}

	cancel := base
	cancel.Kind, cancel.OrderedAt, cancel.Payment = receipts.KindCancellation, ordered.Add(time.Hour), receipts.PayUnknown
	persist(newReceiptRecord(userID, "cancel", cancel))
	_ = pool.QueryRow(ctx, `SELECT status FROM order_receipts WHERE user_id = $1`, userID).Scan(&status)
	if status != "cancelled" {
		t.Errorf("a cancellation cancels: %s", status)
	}
	var txns int
	_ = pool.QueryRow(ctx, `SELECT count(*) FROM ingest_events WHERE user_id = $1`, userID).Scan(&txns)
	if txns != 0 {
		t.Errorf("receipts never become money events: %d", txns)
	}
}
