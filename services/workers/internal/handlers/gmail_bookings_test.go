package handlers

import (
	"sort"
	"strings"
	"testing"

	"github.com/adminos/adminos/workers/internal/bookings"
	"github.com/adminos/adminos/workers/internal/gmail"
)

// AOS-910: a booking email becomes a booking record, never a transaction.
func TestABookingEmailBecomesABookingAndNeverATransaction(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.historyPages["1000|"] = historyPage("1004", "book", "cancel", "offer", "broken")
	f.google.addMessage("book", "IndiGo <noreply@goindigo.in>", "Your IndiGo booking is confirmed", "Thu, 01 Oct 2026 09:30:00 +0530",
		textPart("PNR: AB12CD\nDeparture: 24 Oct 2026 06:15\nTotal Fare ₹5,432.00 paid"))
	f.google.addMessage("cancel", "IndiGo <noreply@goindigo.in>", "Booking AB12CD cancelled", "Fri, 02 Oct 2026 09:30:00 +0530", textPart("PNR: AB12CD"))
	f.google.addMessage("offer", "IndiGo <noreply@goindigo.in>", "Booking offer: 20% off", alertDate, textPart("PNR: ZZ"))
	f.google.addMessage("broken", "IRCTC <ticketadmin@irctc.co.in>", "Booking Confirmation", alertDate, textPart("PNR No. : 4512345678\nTotal Fare ₹ 862.70"))

	r := f.mustRun(t)

	if len(r.Events) != 0 {
		t.Fatalf("a booking is not a money event: %+v", r.Events)
	}
	if r.BookingsFound != 2 || r.NotReceipts != 1 || r.FailedItems != 1 {
		t.Fatalf("found=%d not=%d failed=%d", r.BookingsFound, r.NotReceipts, r.FailedItems)
	}
	b := r.Bookings[0]
	if b.Brand != "IndiGo" || b.Mode != "flight" || *b.JourneyDate != "2026-10-24" || *b.Amount != "5432.00" || b.Cancelled || len(b.RefHash) != 64 {
		t.Errorf("booking: %+v", b)
	}
	c := r.Bookings[1]
	if !c.Cancelled || c.JourneyDate != nil || c.Amount != nil || c.RefHash != b.RefHash {
		t.Errorf("cancellation: %+v", c)
	}
}

func TestEveryBookingParserDomainIsFetchedAndViceVersa(t *testing.T) {
	parsers := bookings.Default().Domains()
	filters := append([]string(nil), gmail.BookingDomains...)
	sort.Strings(parsers)
	sort.Strings(filters)
	if strings.Join(parsers, ",") != strings.Join(filters, ",") {
		t.Errorf("parsers %v\nfilters %v", parsers, filters)
	}
}
