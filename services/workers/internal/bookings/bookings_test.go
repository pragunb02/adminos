package bookings

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/adminos/adminos/workers/internal/receipts"
)

// Synthetic booking emails (AOS-910): the usual layout of each sender, no real names, seats or stations.

var booked = time.Date(2026, 10, 1, 9, 30, 0, 0, ist)

func email(from, subject, body string) receipts.Email {
	return receipts.Email{MessageID: "m", From: from, Subject: subject, Body: body, Date: booked}
}

func TestEverySenderReadsItsBooking(t *testing.T) {
	cases := []struct {
		e    receipts.Email
		want Booking
	}{
		{email("IndiGo <noreply@goindigo.in>", "Your IndiGo booking is confirmed. PNR AB12CD",
			"Booking Reference / PNR: AB12CD\nPassenger 1\nDeparture: Fri, 24 Oct 2026 06:15\nTotal Fare ₹5,432.00"),
			Booking{Brand: "IndiGo", Mode: Flight, PNR: "AB12CD", JourneyDate: "2026-10-24", Amount: 543200}},
		{email("Air India <reservations@airindia.com>", "E-ticket itinerary",
			"PNR Number: XY9Z8W\nDate of Travel: 02-Nov-2026\nTotal Amount\n₹7,100"),
			Booking{Brand: "Air India", Mode: Flight, PNR: "XY9Z8W", JourneyDate: "2026-11-02", Amount: 710000}},
		{email("Akasa Air <noreply@akasaair.com>", "Booking confirmation",
			"PNR: QW7ER5\nJourney Date: 3 November 2026\nAmount Paid: Rs. 4,999"),
			Booking{Brand: "Akasa Air", Mode: Flight, PNR: "QW7ER5", JourneyDate: "2026-11-03", Amount: 499900}},
		{email("IRCTC <ticketadmin@irctc.co.in>", "Booking Confirmation on IRCTC, Train: 12627",
			"PNR No. : 4512345678\nDate of Journey : 24-Oct-2026\nTotal Fare (all inclusive) ₹ 862.70"),
			Booking{Brand: "IRCTC", Mode: Train, PNR: "4512345678", JourneyDate: "2026-10-24", Amount: 86270}},
		{email("redBus <noreply@redbus.in>", "Your redBus ticket", "Ticket No: TK1234\nPNR: RB778899\nDOJ: 25/10/2026\nTotal ₹1,150.00"),
			Booking{Brand: "redBus", Mode: Bus, PNR: "RB778899", JourneyDate: "2026-10-25", Amount: 115000}},
		{email("IndiGo <noreply@goindigo.in>", "Your booking AB12CD has been cancelled", "PNR: AB12CD\nRefund of ₹4,000 initiated"),
			Booking{Brand: "IndiGo", Mode: Flight, PNR: "AB12CD", Cancelled: true}},
	}
	reg := Default()
	for _, c := range cases {
		p := reg.ParserFor(c.e.From)
		if p == nil {
			t.Fatalf("%s: no parser", c.e.From)
		}
		got, err := p.Parse(c.e)
		if err != nil {
			t.Errorf("%s: %v", c.e.Subject, err)
			continue
		}
		c.want.BookedAt = booked
		if got != c.want {
			t.Errorf("%s:\n got %+v\nwant %+v", c.e.Subject, got, c.want)
		}
	}
}

func TestNotBookingsAreSkippedAndBadBookingsFail(t *testing.T) {
	ind := Default().ParserFor("noreply@goindigo.in")
	for _, e := range []receipts.Email{
		email("x@goindigo.in", "Flat 20% off on flights", "PNR: AB12CD"),
		email("x@goindigo.in", "Web check-in is open for PNR AB12CD", "Departure: 24 Oct 2026"),
		email("x@goindigo.in", "Your booking is confirmed", "no reference here"),
		email("x@goindigo.in", "Newsletter", "PNR: AB12CD"),
	} {
		if _, err := ind.Parse(e); !errors.Is(err, ErrNotABooking) {
			t.Errorf("%q: want ErrNotABooking, got %v", e.Subject, err)
		}
	}
	for _, body := range []string{
		"PNR: AB12CD\nTotal Fare ₹5,000",                            // no journey date
		"PNR: AB12CD\nDeparture: 24 Oct 2026",                       // no amount
		"PNR: AB12CD\nDeparture: 24 Oct 2026\nTotal Fare ₹9,00,000", // over the cap
		"PNR: AB12CD\nDeparture: 24 Oct 2029\nTotal Fare ₹5,000",    // more than 400 days after booking
		"PNR: AB12CD\nDeparture: 20 Sep 2026\nTotal Fare ₹5,000",    // before the booking
		"PNR: AB12CD\nDeparture: 31 Feb 2027\nTotal Fare ₹5,000",    // no such date
		"PNR: AB12CD\nDeparture: ２４ Oct 2026\nTotal Fare ₹5,000",    // look-alike digits
	} {
		if _, err := ind.Parse(email("x@goindigo.in", "Booking confirmed", body)); !errors.Is(err, ErrInvalidBooking) {
			t.Errorf("%q: want ErrInvalidBooking, got %v", body, err)
		}
	}
	noDate := email("x@goindigo.in", "Booking confirmed", "PNR: AB12CD\nDeparture: 24 Oct 2026\nTotal ₹10")
	noDate.Date = time.Time{}
	if _, err := ind.Parse(noDate); !errors.Is(err, ErrInvalidBooking) {
		t.Error("an email without a date can't place the booking")
	}
}

func TestSpoofedSendersHaveNoParser(t *testing.T) {
	reg := Default()
	for _, from := range []string{`"IndiGo" <noreply@evil.example>`, "IRCTC <x@irctc.co.in.evil.example>", "x@lrctc.co.in", ""} {
		if reg.ParserFor(from) != nil {
			t.Errorf("%q must have no parser", from)
		}
	}
	if len(reg.Domains()) != 6 {
		t.Errorf("domains: %v", reg.Domains())
	}
}

func TestHostileBodiesFailClosedQuickly(t *testing.T) {
	ind := Default().ParserFor("noreply@goindigo.in")
	start := time.Now()
	big := "PNR: AB12CD\n" + strings.Repeat("Departure: <script>alert(1)</script>\n", 100_000) + "Total ₹1"
	if _, err := ind.Parse(email("x@goindigo.in", "Booking confirmed", big)); err == nil {
		t.Error("no readable date: refused")
	}
	b, err := ind.Parse(email("x@goindigo.in", "Booking confirmed", "PNR: AB12CD<img>\nDeparture: 24 Oct 2026\nTotal ₹100"))
	if err != nil || b.PNR != "AB12CD" {
		t.Errorf("the PNR is letters and digits only: %+v %v", b, err)
	}
	if time.Since(start) > 3*time.Second {
		t.Error("too slow")
	}
	if strings.Contains(b.RefHash(), "AB12CD") || len(b.RefHash()) != 64 {
		t.Error("the PNR is stored only as a hash")
	}
	if (Booking{Brand: "IndiGo", PNR: " ab12cd "}).RefHash() != b.RefHash() {
		t.Error("the same PNR, however written, is the same booking")
	}
	// The same vector is asserted by the API (TripsIntegrationTest), so SMS and email bookings share one key.
	if b.RefHash() != "8b7ce9dd76109096cde382c5181d29d625e769f1f27339117aaa6385b5f00700" {
		t.Errorf("cross-language vector: %s", b.RefHash())
	}
}
