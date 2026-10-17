package handlers

import "testing"

// AOS-503: bills from email get an ISO due date (or are skipped), a type and the card's last 4.

func TestNormalizeDueDateReadsIndianFormats(t *testing.T) {
	cases := map[string]string{
		"2026-11-05":       "2026-11-05",
		"05/11/2026":       "2026-11-05", // day first: Postgres would have read 11 May
		"5/11/2026":        "2026-11-05",
		"05-11-2026":       "2026-11-05",
		"5 November 2026":  "2026-11-05",
		"5 Nov 2026":       "2026-11-05",
		"November 5, 2026": "2026-11-05",
		"  5   Nov  2026 ": "2026-11-05",
	}
	for in, want := range cases {
		got, ok := normalizeDueDate(in)
		if !ok || got != want {
			t.Errorf("normalizeDueDate(%q) = %q, %v; want %q", in, got, ok, want)
		}
	}
	for _, bad := range []string{"", "soon", "31/02/2026", "2026-13-01"} {
		if got, ok := normalizeDueDate(bad); ok {
			t.Errorf("normalizeDueDate(%q) = %q; want not ok", bad, got)
		}
	}
}

func TestDetectBillTypeUsesWholeWords(t *testing.T) {
	cases := map[string]string{
		"your hdfc bank credit card statement is ready": "credit_card",
		"bescom electricity bill for october":           "electricity",
		"airtel broadband bill":                         "internet",
		"your jio postpaid bill":                        "mobile",
		"lic premium due":                               "insurance",
		"home loan emi due on":                          "emi",
		"bwssb water bill":                              "water",
		"rent due for november":                         "rent",
		"your bill is ready":                            "",
		"premium plan renewed":                          "insurance", // "premium" is insurance wording, not "emi"
	}
	for in, want := range cases {
		if got := detectBillType(in); got != want {
			t.Errorf("detectBillType(%q) = %q; want %q", in, got, want)
		}
	}
	if billTypeOrOther("") != "other" || billTypeOrOther("emi") != "emi" {
		t.Error("billTypeOrOther")
	}
}

func TestBillEmailBecomesATypedBillWithItsCardNumber(t *testing.T) {
	h := &GmailHandler{fingerprints: map[string]bool{}}
	result := &GmailResult{}
	data := parseFinancialEmail(GmailEmail{MessageID: "b1", From: "HDFC Bank <cards@hdfcbank.net>", Date: "Fri, 16 Oct 2026 09:12:00 +0530",
		Subject: "Your HDFC Bank Credit Card statement", Body: "Total amount due Rs 18,450.00 on card XX4521. Payment due date: 05/11/2026"})
	if data == nil || data.Type != "bill" {
		t.Fatalf("expected a bill, got %+v", data)
	}
	h.processBill("user-1", data, result)
	if len(result.Bills) != 1 {
		t.Fatalf("want 1 bill, got %d (failed %d)", len(result.Bills), result.FailedItems)
	}
	b := result.Bills[0]
	if b.DueDate != "2026-11-05" || b.BillType != "credit_card" || b.AccountRef == nil || *b.AccountRef != "4521" {
		t.Errorf("bill: due=%s type=%s ref=%v", b.DueDate, b.BillType, b.AccountRef)
	}
}

func TestBillWithoutADueDateIsSkippedNotFatal(t *testing.T) {
	h := &GmailHandler{fingerprints: map[string]bool{}}
	result := &GmailResult{}
	h.processBill("user-1", &ExtractedFinancialData{Type: "bill", BillerName: "Some Biller", Amount: 100, BillDueDate: ""}, result)
	if len(result.Bills) != 0 || result.FailedItems != 1 || result.NetNewItems != 0 {
		t.Errorf("want skipped bill counted as failed: %+v", result)
	}
}
