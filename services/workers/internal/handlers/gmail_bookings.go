package handlers

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/mail"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/adminos/adminos/workers/internal/bookings"
	"github.com/adminos/adminos/workers/internal/receipts"
)

// BookingRecord is one travel booking for travel_bookings (V40, AOS-910): structured fields only.
type BookingRecord struct {
	UserID      string
	SourceRef   string
	Brand       string
	Mode        string
	RefHash     string
	JourneyDate *string // yyyy-mm-dd; nil for a cancellation
	BookedAt    time.Time
	Amount      *string
	Cancelled   bool
}

// processBooking reads a travel booking. Not a booking is skipped (counted with NotReceipts); a booking
// that doesn't hold is a failure, logged by parser and message ID only (D-33).
func processBooking(userID string, parser bookings.Parser, email GmailEmail, result *GmailResult) {
	date, err := mail.ParseDate(email.Date)
	if err != nil {
		date = time.Time{}
	}
	b, err := parser.Parse(receipts.Email{MessageID: email.MessageID, From: email.From, Subject: email.Subject, Body: email.Body, Date: date})
	switch {
	case errors.Is(err, bookings.ErrNotABooking):
		result.NotReceipts++
		return
	case err != nil:
		result.FailedItems++
		log.Printf("[gmail] Booking in message %s not read by the %s parser: %v", email.MessageID, parser.Name(), err)
		return
	case email.MessageID == "":
		result.FailedItems++
		return
	}
	rec := BookingRecord{UserID: userID, SourceRef: email.MessageID, Brand: b.Brand, Mode: string(b.Mode), RefHash: b.RefHash(),
		BookedAt: b.BookedAt, Amount: paiseOrNil(b.Amount), Cancelled: b.Cancelled}
	if b.JourneyDate != "" {
		d := b.JourneyDate
		rec.JourneyDate = &d
	}
	result.BookingsFound++
	result.Bookings = append(result.Bookings, rec)
}

// upsertBooking merges the confirmation and a later cancellation of one booking: blanks are filled,
// the earliest booking time wins, and a cancellation cancels.
const upsertBooking = `
INSERT INTO travel_bookings (user_id, source, source_ref, brand, mode, ref_hash, journey_date, booked_at, amount, cancelled)
VALUES ($1, 'gmail', $2, $3, $4, $5, $6::date, $7, $8::numeric, $9)
ON CONFLICT (user_id, brand, ref_hash) DO UPDATE SET
	journey_date = COALESCE(travel_bookings.journey_date, EXCLUDED.journey_date),
	amount       = COALESCE(travel_bookings.amount, EXCLUDED.amount),
	booked_at    = LEAST(travel_bookings.booked_at, EXCLUDED.booked_at),
	cancelled    = travel_bookings.cancelled OR EXCLUDED.cancelled,
	updated_at   = now()
RETURNING (xmax = 0)`

func persistBookings(ctx context.Context, tx pgx.Tx, r *GmailResult) error {
	for _, b := range r.Bookings {
		var inserted bool
		if err := tx.QueryRow(ctx, upsertBooking, b.UserID, b.SourceRef, b.Brand, b.Mode, b.RefHash, b.JourneyDate, b.BookedAt, b.Amount, b.Cancelled).
			Scan(&inserted); err != nil {
			return fmt.Errorf("upsert travel booking: %w", err)
		}
		if inserted {
			r.NetNewItems++
		} else {
			r.DuplicateItems++
		}
	}
	return nil
}
