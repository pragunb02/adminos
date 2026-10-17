package handlers

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/adminos/adminos/workers/internal/receipts"
)

// ReceiptRecord is one order receipt for order_receipts (V50, AOS-903): structured fields only.
// The order number is already hashed; item names and addresses were never read into it (D-33).
type ReceiptRecord struct {
	UserID       string
	SourceRef    string // the Gmail message ID of this email
	App          string
	Vertical     string
	Status       string // "placed" or "cancelled"
	OrderRefHash string
	OrderedAt    time.Time
	OrderTotal   *string // exact decimal; nil when this email doesn't say (a cancellation or refund)
	ItemCount    *int
	PaymentMode  string
	PaidAmount   *string
	Refund       *string
	Fees         []ReceiptFeeRecord
}

// ReceiptFeeRecord is one fee of a receipt: its kind and the exact amount charged.
type ReceiptFeeRecord struct {
	Kind   string
	Amount string
}

func newReceiptRecord(userID, messageID string, r receipts.Receipt) ReceiptRecord {
	rec := ReceiptRecord{
		UserID: userID, SourceRef: messageID, App: r.App, Vertical: string(r.Vertical), Status: "placed",
		OrderRefHash: r.OrderRefHash(), OrderedAt: r.OrderedAt, PaymentMode: string(r.Payment),
		OrderTotal: paiseOrNil(r.Total), PaidAmount: paiseOrNil(r.PaidAmount), Refund: paiseOrNil(r.RefundAmount),
	}
	if r.Kind == receipts.KindCancellation {
		rec.Status = "cancelled"
	}
	if r.ItemCount > 0 {
		n := r.ItemCount
		rec.ItemCount = &n
	}
	for _, f := range r.Fees {
		rec.Fees = append(rec.Fees, ReceiptFeeRecord{Kind: string(f.Kind), Amount: f.Amount.String()})
	}
	return rec
}

func paiseOrNil(p receipts.Paise) *string {
	if p <= 0 {
		return nil
	}
	s := p.String()
	return &s
}

// upsertReceipt merges every email about one order into one row, keyed by (user, app, order hash):
//   - blanks are filled, values are never overwritten (the first email's total, items, payment win);
//   - the earliest time is the order's time (a "delivered" email comes later);
//   - a cancellation cancels, whichever email came first.
//
// RETURNING (xmax = 0) is true when the row was inserted rather than merged.
const upsertReceipt = `
INSERT INTO order_receipts (user_id, app, vertical, order_ref_hash, source_ref, ordered_at, status,
	order_total, item_count, payment_mode, paid_amount)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8::numeric, $9, $10, $11::numeric)
ON CONFLICT (user_id, app, order_ref_hash) DO UPDATE SET
	ordered_at   = LEAST(order_receipts.ordered_at, EXCLUDED.ordered_at),
	status       = CASE WHEN EXCLUDED.status = 'cancelled' THEN 'cancelled' ELSE order_receipts.status END,
	order_total  = COALESCE(order_receipts.order_total, EXCLUDED.order_total),
	item_count   = COALESCE(order_receipts.item_count, EXCLUDED.item_count),
	payment_mode = CASE WHEN order_receipts.payment_mode = 'unknown' THEN EXCLUDED.payment_mode ELSE order_receipts.payment_mode END,
	paid_amount  = COALESCE(order_receipts.paid_amount, EXCLUDED.paid_amount),
	updated_at   = now()
RETURNING id, (xmax = 0)`

// persistReceipts writes the sync's receipts with their fees and refunds, in the sync's transaction.
// A fee kind already recorded keeps its amount; the same refund amount announced twice ("initiated",
// then "credited") is one refund.
func persistReceipts(ctx context.Context, tx pgx.Tx, r *GmailResult) error {
	for _, rec := range r.Receipts {
		var id string
		var inserted bool
		if err := tx.QueryRow(ctx, upsertReceipt, rec.UserID, rec.App, rec.Vertical, rec.OrderRefHash, rec.SourceRef,
			rec.OrderedAt, rec.Status, rec.OrderTotal, rec.ItemCount, rec.PaymentMode, rec.PaidAmount).Scan(&id, &inserted); err != nil {
			return fmt.Errorf("upsert order receipt: %w", err)
		}
		if inserted {
			r.NetNewItems++
		} else {
			r.DuplicateItems++
		}
		for _, f := range rec.Fees {
			if _, err := tx.Exec(ctx, `INSERT INTO order_receipt_fees (receipt_id, kind, amount) VALUES ($1, $2, $3::numeric)
				ON CONFLICT (receipt_id, kind) DO NOTHING`, id, f.Kind, f.Amount); err != nil {
				return fmt.Errorf("insert receipt fee: %w", err)
			}
		}
		if rec.Refund != nil {
			if _, err := tx.Exec(ctx, `INSERT INTO order_receipt_refunds (receipt_id, amount, source_ref, refunded_at)
				VALUES ($1, $2::numeric, $3, $4) ON CONFLICT (receipt_id, amount) DO NOTHING`, id, *rec.Refund, rec.SourceRef, rec.OrderedAt); err != nil {
				return fmt.Errorf("insert receipt refund: %w", err)
			}
		}
	}
	return nil
}
