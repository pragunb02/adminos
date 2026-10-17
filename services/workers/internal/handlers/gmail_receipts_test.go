package handlers

import (
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/adminos/adminos/workers/internal/gmail"
	"github.com/adminos/adminos/workers/internal/receipts"
)

// AOS-903: order receipts are read by their sender's parser and never become a second spend.

const swiggyReceipt = "Order No: 187654321098\nItems (3)\nItem Total ₹400.00\nDelivery Fee ₹35.00\nPlatform fee ₹10.00\n" +
	"Delivery partner tip ₹20.00\nGrand Total ₹465.00\nPaid via UPI ₹465.00"

func TestAReceiptEmailBecomesAReceiptAndNeverATransaction(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.historyPages["1000|"] = historyPage("1005", "receipt", "alert", "promo", "broken")
	f.google.addMessage("receipt", "Swiggy <noreply@swiggy.in>", "Your Swiggy order summary", "Sat, 17 Oct 2026 21:40:00 +0530", textPart(swiggyReceipt))
	f.google.addMessage("alert", hdfc, "Transaction alert", alertDate, textPart("Rs 465.00 has been debited from your A/c XX1234 at SWIGGY"))
	f.google.addMessage("promo", "Swiggy <offers@swiggy.in>", "Order now: 50% off tonight", alertDate, textPart("Order now and pay ₹99 only"))
	f.google.addMessage("broken", "Zepto <noreply@zeptonow.com>", "Your Zepto order summary", alertDate,
		textPart("Order ID: ZPT-ZZ99YY88\nHandling Fee ₹13.00\nSmall Cart Fee ₹35.00\nGrand Total ₹40.00"))

	r := f.mustRun(t)

	if len(r.Events) != 1 || r.Events[0].SourceRef != "alert" {
		t.Fatalf("only the bank alert is a money event, got %+v", r.Events)
	}
	if r.ReceiptsFound != 1 || len(r.Receipts) != 1 || r.NotReceipts != 1 || r.ReceiptFailures != 1 || r.FailedItems != 1 {
		t.Fatalf("found=%d receipts=%d not=%d failures=%d failed=%d", r.ReceiptsFound, len(r.Receipts), r.NotReceipts, r.ReceiptFailures, r.FailedItems)
	}
	rec := r.Receipts[0]
	if rec.App != "Swiggy" || rec.Vertical != "food_delivery" || rec.Status != "placed" || rec.SourceRef != "receipt" ||
		*rec.OrderTotal != "465.00" || *rec.ItemCount != 3 || rec.PaymentMode != "upi" || *rec.PaidAmount != "465.00" || rec.Refund != nil {
		t.Errorf("record: %+v", rec)
	}
	if !rec.OrderedAt.Equal(time.Date(2026, 10, 17, 16, 10, 0, 0, time.UTC)) {
		t.Errorf("ordered at %v", rec.OrderedAt)
	}
	want := []ReceiptFeeRecord{{"delivery", "35.00"}, {"platform", "10.00"}, {"tip", "20.00"}}
	if len(rec.Fees) != 3 || rec.Fees[0] != want[0] || rec.Fees[1] != want[1] || rec.Fees[2] != want[2] {
		t.Errorf("fees: %+v", rec.Fees)
	}
	if strings.Contains(rec.OrderRefHash, "187654321098") || len(rec.OrderRefHash) != 64 {
		t.Error("the order number is stored only as a hash")
	}
}

func TestReceiptRecordsOfCancellationsAndRefunds(t *testing.T) {
	at := time.Date(2026, 10, 17, 4, 30, 0, 0, time.UTC)
	cancel := newReceiptRecord(testUser, "m1", receipts.Receipt{App: "Zomato", Vertical: receipts.FoodDelivery, Kind: receipts.KindCancellation,
		OrderID: "6655443322", OrderedAt: at, Payment: receipts.PayUnknown})
	if cancel.Status != "cancelled" || cancel.OrderTotal != nil || cancel.ItemCount != nil || cancel.PaidAmount != nil || len(cancel.Fees) != 0 {
		t.Errorf("cancellation: %+v", cancel)
	}
	refund := newReceiptRecord(testUser, "m2", receipts.Receipt{App: "Zomato", Vertical: receipts.FoodDelivery, Kind: receipts.KindRefund,
		OrderID: "6655443322", OrderedAt: at, Payment: receipts.PayUnknown, RefundAmount: 12000})
	if refund.Status != "placed" || refund.Refund == nil || *refund.Refund != "120.00" || refund.OrderRefHash != cancel.OrderRefHash {
		t.Errorf("refund: %+v", refund)
	}
}

func TestAReceiptWithoutAReadableDateIsAFailure(t *testing.T) {
	result := &GmailResult{}
	processReceipt(testUser, receipts.Zepto(), GmailEmail{MessageID: "m", From: "noreply@zeptonow.com", Subject: "Your Zepto order",
		Date: "not a date", Body: "Order ID: ZPT-AB12CD34\nGrand Total ₹213.00"}, result)
	if result.ReceiptFailures != 1 || len(result.Receipts) != 0 {
		t.Errorf("got %+v", result)
	}
	processReceipt(testUser, receipts.Zepto(), GmailEmail{From: "noreply@zeptonow.com", Subject: "Your Zepto order",
		Date: alertDate, Body: "Order ID: ZPT-AB12CD34\nGrand Total ₹213.00"}, result)
	if result.FailedItems != 2 || len(result.Receipts) != 0 {
		t.Errorf("a message without an ID can't be stored: %+v", result)
	}
}

// The sender filters and the parsers name the same domains, so a receipt that is fetched always has a
// parser, and a parser never waits for mail that is never fetched.
func TestEveryReceiptParserDomainIsFetchedAndViceVersa(t *testing.T) {
	parsers := receipts.Default().Domains()
	filters := append([]string(nil), gmail.ReceiptDomains...)
	sort.Strings(parsers)
	sort.Strings(filters)
	if strings.Join(parsers, ",") != strings.Join(filters, ",") {
		t.Errorf("parsers %v\nfilters %v", parsers, filters)
	}
	for _, d := range parsers {
		for _, f := range gmail.FinancialDomains {
			if d == f {
				t.Errorf("%s is both a bank-alert and a receipt sender", d)
			}
		}
	}
}
