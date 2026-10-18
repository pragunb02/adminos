//go:build integration

package handlers

import (
	"context"
	"testing"
	"time"

	"github.com/google/uuid"
)

// AOS-910 on Postgres: the confirmation and the cancellation of one booking are one row.
func TestGmailPersistMergesABookingAndItsCancellation_Integration(t *testing.T) {
	pool := testPool(t)
	defer pool.Close()
	ctx := context.Background()
	userID := createTestUser(t, ctx, pool)
	connID := createTestConnection(t, ctx, pool, userID)
	h := NewGmailHandler(pool, nil, GmailConfig{})
	date, amount := "2026-10-24", "5432.00"
	booked := time.Date(2026, 10, 1, 4, 0, 0, 0, time.UTC)
	hash := "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
	for _, rec := range []BookingRecord{
		{UserID: userID, SourceRef: "book", Brand: "IndiGo", Mode: "flight", RefHash: hash, JourneyDate: &date, BookedAt: booked, Amount: &amount},
		{UserID: userID, SourceRef: "cancel", Brand: "IndiGo", Mode: "flight", RefHash: hash, BookedAt: booked.Add(24 * time.Hour), Cancelled: true},
	} {
		sessionID := uuid.New().String()
		if _, err := pool.Exec(ctx, `INSERT INTO sync_sessions (id, user_id, connection_id, sync_type) VALUES ($1, $2, $3, 'manual')`, sessionID, userID, connID); err != nil {
			t.Fatalf("session: %v", err)
		}
		if err := h.Persist(ctx, &GmailResult{SyncSessionID: sessionID, UserID: userID, ConnectionID: connID, Bookings: []BookingRecord{rec}, BookingsFound: 1}); err != nil {
			t.Fatalf("persist: %v", err)
		}
	}
	var rows int
	var journey, amt string
	var cancelled bool
	if err := pool.QueryRow(ctx, `SELECT count(*) OVER (), journey_date::text, amount::text, cancelled FROM travel_bookings WHERE user_id = $1`, userID).
		Scan(&rows, &journey, &amt, &cancelled); err != nil {
		t.Fatalf("query: %v", err)
	}
	if rows != 1 || journey != "2026-10-24" || amt != "5432.00" || !cancelled {
		t.Errorf("rows=%d journey=%s amount=%s cancelled=%v", rows, journey, amt, cancelled)
	}
}
