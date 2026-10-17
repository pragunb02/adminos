package gmail

import (
	"strings"
	"testing"
)

func TestSearchQueryIsBoundedAndFilteredBySenderOrSubject(t *testing.T) {
	q := SearchQuery(30)

	if !strings.HasPrefix(q, "newer_than:30d (from:(") {
		t.Errorf("query must start with the window and the senders: %s", q)
	}
	for _, want := range []string{"hdfcbank.net OR ", "icicibank.com", "googlepay-noreply@google.com", `subject:("transaction alert" OR `, `"has been debited"`} {
		if !strings.Contains(q, want) {
			t.Errorf("want %q in %s", want, q)
		}
	}
}

func TestIsFinancial(t *testing.T) {
	cases := []struct {
		from, subject string
		want          bool
	}{
		{"HDFC Bank InstaAlerts <alerts@hdfcbank.net>", "You have done a UPI txn", true},
		{"credit_cards@icicibank.com", "Your ICICI Bank Credit Card statement", true},
		{"SBI Card <statements@mail.sbicard.com>", "e-statement", true}, // subdomain
		{"Kotak <noreply@kotak.bank.in>", "Alert", true},                // RBI .bank.in
		{"Google Pay <googlepay-noreply@google.com>", "You paid ₹250", true},
		{"Ravi <ravi@gmail.com>", "Transaction alert: Rs 500 has been debited", true}, // subject match
		{"Ravi <ravi@gmail.com>", "Dinner on Friday?", false},
		{"Google <no-reply@accounts.google.com>", "Security alert", false},
		{"Phisher <alerts@hdfcbank.net.evil.com>", "Hello", false}, // look-alike domain
		{"Fake <x@nothdfcbank.net>", "Hi", false},
		{"", "", false},
	}
	for _, c := range cases {
		if got := IsFinancial(c.from, c.subject); got != c.want {
			t.Errorf("IsFinancial(%q, %q) = %v, want %v", c.from, c.subject, got, c.want)
		}
	}
}

func TestSenderAddressHandlesMalformedHeaders(t *testing.T) {
	for from, want := range map[string]string{
		`"HDFC, Bank" <Alerts@HDFCBank.net>`: "alerts@hdfcbank.net",
		`HDFC Bank <alerts@hdfcbank.net`:     "hdfc bank <alerts@hdfcbank.net",
		`broken <alerts@hdfcbank.net> extra`: "alerts@hdfcbank.net",
		`ALERTS@HDFCBANK.NET`:                "alerts@hdfcbank.net",
	} {
		if got := senderAddress(from); got != want {
			t.Errorf("senderAddress(%q) = %q, want %q", from, got, want)
		}
	}
}
