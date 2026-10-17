package handlers

import (
	"testing"

	"github.com/adminos/adminos/workers/internal/gmail"
)

// AOS-909: pay-later provider email. Every email here is synthetic.

func TestPayLaterProviderIsDecidedByTheSenderDomain(t *testing.T) {
	cases := []struct {
		from, subject, body, want string
	}{
		{"LazyPay <noreply@lazypay.in>", "Your bill", "", "LazyPay"},
		{"Simpl <hello@mail.getsimpl.com>", "Statement", "", "Simpl"},
		{"Amazon Pay <no-reply@amazonpay.in>", "Your Amazon Pay Later bill", "", "Amazon Pay Later"},
		{"Flipkart <noreply@flipkart.com>", "Your Flipkart Pay Later bill is ready", "", "Flipkart Pay Later"},
		{"Flipkart <noreply@flipkart.com>", "Your order has been placed", "Paid via Flipkart Pay Later", ""}, // a receipt, read by the receipt parser
		{"Amazon Pay <no-reply@amazonpay.in>", "Cashback credited", "", ""},                                  // the wallet, not pay-later
		{`"LazyPay" <bills@evil.example>`, "Your LazyPay bill", "", ""},                                      // a display name never counts
		{"LazyPay <noreply@lazypay.in.evil.example>", "Your LazyPay bill", "", ""},                           // look-alike domain
		{"broken header", "Your LazyPay bill", "", ""},
	}
	for _, c := range cases {
		got := ""
		if p := payLaterProviderFor(GmailEmail{From: c.from, Subject: c.subject, Body: c.body}); p != nil {
			got = p.name
		}
		if got != c.want {
			t.Errorf("payLaterProviderFor(%q, %q) = %q, want %q", c.from, c.subject, got, c.want)
		}
	}
}

func TestPayLaterEmailsBecomeBillsAndTaggedPurchases(t *testing.T) {
	h := &GmailHandler{fingerprints: map[string]bool{}}
	r := &GmailResult{}
	date := "Sun, 18 Oct 2026 09:00:00 +0530"
	emails := []GmailEmail{
		{MessageID: "bill", From: "LazyPay <noreply@lazypay.in>", Subject: "Your LazyPay bill", Date: date,
			Body: "Total amount due: Rs 1,234.00\nDue date: 03-Nov-2026"},
		{MessageID: "buy", From: "Simpl <noreply@getsimpl.com>", Subject: "Transaction successful", Date: date,
			Body: "You spent Rs 450.00 at Swiggy using Simpl."},
		{MessageID: "repaid", From: "Simpl <noreply@getsimpl.com>", Subject: "Payment received", Date: date,
			Body: "Thank you for your payment of Rs 450.00 towards your Simpl bill."},
		{MessageID: "offer", From: "LazyPay <offers@lazypay.in>", Subject: "Your limit is now higher", Date: date, Body: "Enjoy Rs 10,000 limit"},
		{MessageID: "huge", From: "LazyPay <noreply@lazypay.in>", Subject: "Your bill", Date: date,
			Body: "Total amount due: Rs 99,99,99,999.00\nDue date: 03-Nov-2026"},
		{MessageID: "nodate", From: "LazyPay <noreply@lazypay.in>", Subject: "Your bill", Date: date, Body: "Total amount due: Rs 99.00"},
	}
	for _, e := range emails {
		if !h.processPayLater(testUser, e, r) {
			t.Fatalf("%s: a provider email must be handled as pay-later", e.MessageID)
		}
	}
	if len(r.Bills) != 1 || r.Bills[0].BillType != "pay_later" || r.Bills[0].BillerName != "LazyPay" || r.Bills[0].DueDate != "2026-11-03" || r.Bills[0].Amount != 1234 {
		t.Errorf("bills: %+v", r.Bills)
	}
	if len(r.Events) != 1 || *r.Events[0].PaymentMethod != "pay_later" || *r.Events[0].Metadata != `{"payLater":"Simpl"}` ||
		*r.Events[0].MerchantRaw != "Swiggy" || r.Events[0].Amount != "450.00" || r.Events[0].Direction != "debit" {
		t.Errorf("events: %+v", r.Events)
	}
	if r.PayLaterSkipped != 2 || r.FailedItems != 2 {
		t.Errorf("skipped %d (repayment and offer), failed %d (huge and no due date)", r.PayLaterSkipped, r.FailedItems)
	}
	if h.processPayLater(testUser, GmailEmail{From: "Swiggy <noreply@swiggy.in>", Subject: "Your order", Body: "Paid via Simpl"}, r) {
		t.Error("a receipt that mentions a provider is still the app's receipt")
	}
}

func TestPayLaterDomainsAreInTheSenderFilters(t *testing.T) {
	for _, d := range PayLaterDomains {
		if !gmail.IsFinancial("x <a@"+d+">", "anything") {
			t.Errorf("%s must be fetched", d)
		}
	}
	if !gmail.IsFinancial("Flipkart <noreply@flipkart.com>", "Your Flipkart Pay Later bill") {
		t.Error("Flipkart's pay-later bills are fetched through the receipt subjects")
	}
}

func TestPayLaterKinds(t *testing.T) {
	cases := map[string]payLaterKind{
		"Your bill of Rs 500 is due on 3 Nov 2026":        payLaterBill,
		"Rs 450 spent at Swiggy using LazyPay. Total due": payLaterPurchase,
		"Payment of Rs 500 received. Thank you":           payLaterRepayment,
		"Your limit is now Rs 10,000":                     payLaterOther,
	}
	for in, want := range cases {
		if got := payLaterKindOf(in); got != want {
			t.Errorf("payLaterKindOf(%q) = %d, want %d", in, got, want)
		}
	}
	if got, ok := normalizeDueDate("03-NOV-2026"); !ok || got != "2026-11-03" {
		t.Errorf("dd-MMM-yyyy due dates: %q %v", got, ok)
	}
}
