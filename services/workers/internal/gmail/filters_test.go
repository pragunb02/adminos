package gmail

import (
	"strings"
	"testing"
)

func TestSearchQueryIsBoundedAndFilteredBySenderOnly(t *testing.T) {
	q := SearchQuery(30)

	if !strings.HasPrefix(q, "newer_than:30d (from:(") || !strings.HasSuffix(q, ")") {
		t.Errorf("query must be the window and the senders: %s", q)
	}
	for _, want := range []string{"hdfcbank.net OR ", "icicibank.com", "bank.in", "googlepay-noreply@google.com"} {
		if !strings.Contains(q, want) {
			t.Errorf("want %q in %s", want, q)
		}
	}
	// A subject never admits a message (AUD-11): the only subject clause is tied to the receipt
	// senders (AOS-903), checked by TestSearchQueryReadsReceiptSendersOnlyWithOrderSubjects.
	if strings.Count(q, "subject:") != 1 || !strings.Contains(q, "flipkart.com) subject:(") {
		t.Errorf("a subject must never admit a message on its own (AUD-11): %s", q)
	}
}

func TestSenderOrganisation(t *testing.T) {
	for addr, want := range map[string]string{
		"alerts@hdfcbank.net":          "hdfcbank.net",
		"statements@mail.sbicard.com":  "sbicard.com", // subdomain
		"noreply@kotak.bank.in":        "kotak.bank.in",
		"noreply@alerts.kotak.bank.in": "kotak.bank.in", // each <bank>.bank.in is its own organisation
		"x@bank.in":                    "",              // the registry itself is no bank
		"googlepay-noreply@google.com": "google.com",
		"no-reply@accounts.google.com": "", // only the listed Google address
		"alerts@hdfcbank.net.evil.com": "", // look-alike domain
		"x@nothdfcbank.net":            "",
		"ravi@gmail.com":               "",
		"@hdfcbank.net":                "",
		"alerts@":                      "",
		"":                             "",
	} {
		if got := senderOrganisation(addr); got != want {
			t.Errorf("senderOrganisation(%q) = %q, want %q", addr, got, want)
		}
	}
}

func TestSenderAddressAcceptsOnlyOneWellFormedAddress(t *testing.T) {
	for from, want := range map[string]string{
		`"HDFC, Bank" <Alerts@HDFCBank.net>`:                 "alerts@hdfcbank.net",
		`ALERTS@HDFCBANK.NET`:                                "alerts@hdfcbank.net",
		`=?windows-1252?Q?HDFC_Bank?= <alerts@hdfcbank.net>`: "alerts@hdfcbank.net", // any charset in the name
		`"alerts@hdfcbank.net" <x@evil.example>`:             "x@evil.example",      // the name is not the address
		`HDFC Bank <alerts@hdfcbank.net`:                     "",                    // malformed: no lenient fallback
		`broken <alerts@hdfcbank.net> extra`:                 "",
		`alerts@hdfcbank.net, x@evil.example`:                "", // two senders
		``:                                                   "",
	} {
		if got := senderAddress(from); got != want {
			t.Errorf("senderAddress(%q) = %q, want %q", from, got, want)
		}
	}
}

// AOS-903: receipt senders are candidates only for order-like subjects, so their promotions stay
// unread; like bank mail they are then read only when authenticated (authn_test.go).
func TestReceiptSendersAreReadOnlyForOrderSubjects(t *testing.T) {
	cases := []struct {
		from, subject string
		want          bool
	}{
		{"Swiggy <noreply@swiggy.in>", "Your Swiggy order summary", true},
		{"Zomato <noreply@zomato.com>", "Refund initiated for your order", true},
		{"Amazon.in <auto-confirm@amazon.in>", "Your Amazon.in order #402-1234567-7654321", true},
		{"blinkit <noreply@blinkit.com>", "Your Blinkit bill", true},
		{"Zepto <noreply@zeptonow.com>", "Your order has been CANCELLED", true},
		{"Flipkart <noreply@mailer.flipkart.com>", "Delivered: your package", true}, // subdomain
		{"Swiggy <offers@swiggy.in>", "Craving biryani? 50% off tonight", false},
		{"Amazon.in <store-news@amazon.in>", "Great Indian Festival starts now", false},
		{"Zomato <noreply@zomato.com>", "Reorders are here", false}, // whole words only
		{"Phisher <noreply@swiggy.in.evil.com>", "Your order", false},
	}
	for _, c := range cases {
		if got := receiptOrganisation(senderAddress(c.from), c.subject) != ""; got != c.want {
			t.Errorf("receipt sender %q with subject %q = %v, want %v", c.from, c.subject, got, c.want)
		}
	}
}

func TestSearchQueryReadsReceiptSendersOnlyWithOrderSubjects(t *testing.T) {
	q := SearchQuery(30)
	want := "OR (from:(swiggy.in OR swiggy.com OR zomato.com OR zeptonow.com OR zepto.com OR blinkit.com OR grofers.com OR amazon.in OR flipkart.com) " +
		"subject:(order OR delivered OR receipt OR invoice OR refund OR cancelled OR canceled OR bill)))"
	if !strings.HasSuffix(q, want) {
		t.Errorf("want the receipt clause at the end:\n%s", q)
	}
}
