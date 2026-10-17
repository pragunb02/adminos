// Package bookings reads travel booking emails (AOS-910): flights, trains and buses. What matters for
// trips is the journey date (a return ticket is often booked weeks ahead) and the amount, so the
// booking can be tied to its payment. One parser per sender (Strategy), chosen by sender domain.
//
// Privacy (D-33): only the brand, mode, journey date, amount and a hash of the PNR are kept. No
// names, no seats, no stations, no text.
package bookings

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/adminos/adminos/workers/internal/receipts"
)

// Mode is how one travels.
type Mode string

const (
	Flight Mode = "flight"
	Train  Mode = "train"
	Bus    Mode = "bus"
)

// Booking is the structured part of a booking email.
type Booking struct {
	Brand string // the merchant-catalogue brand, e.g. "IndiGo"
	Mode  Mode
	// PNR is only used to compute RefHash; never stored or logged.
	PNR string
	// JourneyDate is the travel date (India's calendar), as yyyy-mm-dd.
	JourneyDate string
	BookedAt    time.Time
	// Amount is what the booking cost; 0 when the email doesn't say (a cancellation).
	Amount    receipts.Paise
	Cancelled bool
}

var (
	// ErrNotABooking: from a booking sender but not a booking (offers, web check-in reminders).
	ErrNotABooking = errors.New("not a travel booking")
	// ErrInvalidBooking: looked like a booking but its fields don't hold; counted as a failure.
	ErrInvalidBooking = errors.New("invalid travel booking")
)

// MaxAmount bounds a booking: anything above ₹5 lakh is a mis-read or a hostile email.
const MaxAmount receipts.Paise = 5_00_000_00

// RefHash is the stored key of a booking: SHA-256 of brand and PNR, so the confirmation and a later
// cancellation land on one row without keeping the PNR.
func (b Booking) RefHash() string {
	sum := sha256.Sum256([]byte(strings.ToLower(b.Brand) + ":" + strings.ToUpper(strings.TrimSpace(b.PNR))))
	return hex.EncodeToString(sum[:])
}

// Parser reads one sender's booking emails.
type Parser interface {
	Name() string
	Domains() []string
	Parse(e receipts.Email) (Booking, error)
}

// Registry picks the parser by sender domain.
type Registry struct{ parsers []Parser }

// Default is every supported booking sender.
func Default() *Registry {
	return &Registry{parsers: []Parser{
		airline("indigo", "IndiGo", "goindigo.in"),
		airline("airindia", "Air India", "airindia.com", "airindia.in"),
		airline("akasa", "Akasa Air", "akasaair.com"),
		layout{name: "irctc", brand: "IRCTC", mode: Train, domains: []string{"irctc.co.in"}},
		layout{name: "redbus", brand: "redBus", mode: Bus, domains: []string{"redbus.in"}},
	}}
}

func airline(name, brand string, domains ...string) Parser {
	return layout{name: name, brand: brand, mode: Flight, domains: domains}
}

// Domains lists every domain the registry reads.
func (r *Registry) Domains() []string {
	var out []string
	for _, p := range r.parsers {
		out = append(out, p.Domains()...)
	}
	return out
}

// ParserFor returns the parser for a From header, or nil.
func (r *Registry) ParserFor(from string) Parser {
	domain := receipts.SenderDomain(from)
	if domain == "" {
		return nil
	}
	for _, p := range r.parsers {
		for _, d := range p.Domains() {
			if domain == d || strings.HasSuffix(domain, "."+d) {
				return p
			}
		}
	}
	return nil
}

type layout struct {
	name, brand string
	mode        Mode
	domains     []string
}

func (l layout) Name() string      { return l.name }
func (l layout) Domains() []string { return l.domains }

var (
	cancelSubject  = regexp.MustCompile(`(?i)\bcancel(?:l?ed|lation)\b`)
	bookingSubject = regexp.MustCompile(`(?i)\b(?:booking|booked|e-?ticket|ticket|itinerary|confirmed|confirmation|pnr)\b`)
	promoSubject   = regexp.MustCompile(`(?i)\b(?:sale|offer|deal|\d{1,2}\s*%\s*off|web check-?in)\b`)
	pnrRE          = regexp.MustCompile(`(?i)\bPNR\s*(?:No\.?|Number)?\s*[:#\-]?\s*([A-Z0-9]{5,10})\b`)
	journeyLabel   = regexp.MustCompile(`(?i)^\W*(?:date\s+of\s+journey|journey\s+date|date\s+of\s+travel|travel\s+date|departure\s+date|departure|doj)\b\s*[:\-]?\s*(.+)$`)
	totals         = receipts.Labels("total fare", "total amount", "amount paid", "grand total", "total price", "total")
	dateLayouts    = []string{"2 Jan 2006", "02 Jan 2006", "2-Jan-2006", "02-Jan-2006", "Mon, 2 Jan 2006", "Mon 2 Jan 2006",
		"2 January 2006", "Monday, 2 January 2006", "02/01/2006", "2/1/2006", "02-01-2006", "2006-01-02", "Jan 2, 2006"}
	dateInText = regexp.MustCompile(`(?i)(?:[A-Za-z]{3,9},?\s+)?\d{1,2}[\s/-](?:[A-Za-z]{3,9}|\d{1,2})[\s/-]\d{4}|\d{4}-\d{2}-\d{2}|[A-Za-z]{3}\s+\d{1,2},\s+\d{4}`)
)

var ist = time.FixedZone("IST", 5*60*60+30*60)

func (l layout) Parse(e receipts.Email) (Booking, error) {
	subject := receipts.Clean(e.Subject)
	cancelled := cancelSubject.MatchString(subject)
	if promoSubject.MatchString(subject) || (!cancelled && !bookingSubject.MatchString(subject)) {
		return Booking{}, ErrNotABooking
	}
	lines := receipts.Lines(e.Body)
	pnr := pnrRE.FindStringSubmatch(subject + "\n" + strings.Join(lines, "\n"))
	if pnr == nil {
		return Booking{}, ErrNotABooking
	}
	if e.Date.IsZero() {
		return Booking{}, fmt.Errorf("%w: no email date", ErrInvalidBooking)
	}
	b := Booking{Brand: l.brand, Mode: l.mode, PNR: pnr[1], BookedAt: e.Date, Cancelled: cancelled}
	if cancelled {
		return b, nil // the confirmation carries the date and amount; the row is merged by RefHash
	}
	journey, ok := journeyDate(lines, e.Date)
	if !ok {
		return Booking{}, fmt.Errorf("%w: no readable journey date", ErrInvalidBooking)
	}
	b.JourneyDate = journey
	amount, ok := receipts.AmountAfter(lines, totals)
	if !ok || amount > MaxAmount {
		return Booking{}, fmt.Errorf("%w: no plausible amount", ErrInvalidBooking)
	}
	b.Amount = amount
	return b, nil
}

// journeyDate reads the first labelled travel date, day first, and refuses dates more than a day
// before the booking or over 400 days after it (booking windows are at most a year).
func journeyDate(lines []string, booked time.Time) (string, bool) {
	local := booked.In(ist)
	bookedDay := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, ist)
	for _, line := range lines {
		m := journeyLabel.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		raw := dateInText.FindString(m[1])
		if raw == "" {
			continue
		}
		raw = strings.Join(strings.Fields(raw), " ")
		for _, layout := range dateLayouts {
			t, err := time.ParseInLocation(layout, raw, ist)
			if err != nil {
				continue
			}
			if t.Before(bookedDay.AddDate(0, 0, -1)) || t.After(bookedDay.AddDate(0, 0, 400)) {
				return "", false
			}
			return t.Format("2006-01-02"), true
		}
	}
	return "", false
}
