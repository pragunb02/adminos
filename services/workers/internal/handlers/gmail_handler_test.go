package handlers

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestDetectDirection(t *testing.T) {
	cases := []struct {
		text, want string
	}{
		{"Rs 500.00 has been debited from your A/c XX1234 and credited to VPA ramu@okicici", "debit"},
		{"INR 75,000 credited to your account XX1234 by NEFT from ACME PVT LTD", "credit"},
		{"Rs 1,200 credited to your credit card ending 4321 (refund from Amazon)", "credit"},
		{"You spent Rs 349 at ZOMATO on your card", "debit"},
		{"Payment of Rs 999 charged on your card at AMAZON", "debit"},
		{"Your refund of Rs 300 from Myntra has been processed", "credit"},
		{"We have received Rs 2,000 from Ravi", "credit"},
		{"Transaction alert for Rs 500", ""},                      // no direction word
		{"Rs 500 paid to Ravi. Rs 500 received from Ravi", ""},    // both, no anchor
		{"Account statement: debits and credits for October", ""}, // "debits" isn't "debited"
	}
	for _, c := range cases {
		if got := detectDirection(c.text); got != c.want {
			t.Errorf("detectDirection(%q) = %q, want %q", c.text, got, c.want)
		}
	}
}

func TestOccurredAtPrefersTheHeader(t *testing.T) {
	got := occurredAt("Fri, 16 Oct 2026 09:12:00 +0530", "2026-10-15")
	if want := time.Date(2026, 10, 16, 3, 42, 0, 0, time.UTC); !got.Equal(want) {
		t.Errorf("header: got %v, want %v", got, want)
	}
	got = occurredAt("", "15/10/2026")
	if want := time.Date(2026, 10, 14, 18, 30, 0, 0, time.UTC); !got.Equal(want) {
		t.Errorf("body date at 00:00 IST: got %v, want %v", got, want)
	}
	if !occurredAt("garbage", "").IsZero() {
		t.Error("unparseable dates must give zero, not now")
	}
}

func TestEmailTransactionsBecomeIngestEvents(t *testing.T) {
	result := &GmailResult{}
	var sum float64
	var n int
	emails := []GmailEmail{
		{MessageID: "m1", From: "HDFC Bank <alerts@hdfcbank.net>", Date: "Fri, 16 Oct 2026 09:12:00 +0530",
			Subject: "Transaction alert", Body: "Rs 75,000.00 credited to your account XX1234 by NEFT from ACME"},
		{MessageID: "m2", From: "HDFC Bank <alerts@hdfcbank.net>", Date: "Fri, 16 Oct 2026 10:00:00 +0530",
			Subject: "Transaction alert", Body: "Rs 349 debited from your A/c XX1234 at ZOMATO"},
		{MessageID: "m3", From: "Bank <a@b.c>", Date: "Fri, 16 Oct 2026 11:00:00 +0530",
			Subject: "Transaction alert", Body: "Transaction of Rs 500 on A/c XX1234"},
	}
	for _, e := range emails {
		data := parseFinancialEmail(e)
		if data == nil || data.Type != "transaction" {
			t.Fatalf("%s: expected a transaction, got %+v", e.MessageID, data)
		}
		processTransaction("user-1", data, result, &sum, &n)
	}
	if len(result.Events) != 2 || result.NeedsReview != 1 {
		t.Fatalf("want 2 events + 1 needs-review, got %d events, %d needs-review", len(result.Events), result.NeedsReview)
	}
	salary, food := result.Events[0], result.Events[1]
	if salary.Direction != "credit" || salary.Amount != "75000.00" || salary.SourceRef != "m1" {
		t.Errorf("salary event: %+v", salary)
	}
	if food.Direction != "debit" || food.Amount != "349.00" || food.AccountHint == nil || *food.AccountHint != "1234" {
		t.Errorf("debit event: %+v", food)
	}
	if b, _ := json.Marshal(result); len(b) == 0 {
		t.Error("result must serialise")
	}
}

func TestExecuteFailsWithoutGmailInsteadOfReportingSuccess(t *testing.T) {
	h := NewGmailHandler(nil, nil, "", "")
	if _, err := h.Execute(context.Background(), &GmailPayload{UserID: "u", ConnectionID: "c"}); err == nil {
		t.Fatal("expected an error when Gmail can't be reached")
	}
}
