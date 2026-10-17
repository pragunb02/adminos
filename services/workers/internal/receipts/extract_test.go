package receipts

import (
	"errors"
	"testing"
	"testing/quick"
	"time"
)

func TestParsePaiseReadsIndianAmounts(t *testing.T) {
	cases := map[string]Paise{"1": 100, "1,234.5": 123450, "1,23,456.78": 12345678, "0.05": 5, "99.999": 9999}
	for in, want := range cases {
		if got, ok := parsePaise(in); !ok || got != want {
			t.Errorf("parsePaise(%q) = %d, %v; want %d", in, got, ok, want)
		}
	}
	for _, bad := range []string{"", "abc", "99999999999"} {
		if _, ok := parsePaise(bad); ok {
			t.Errorf("parsePaise(%q) should fail", bad)
		}
	}
}

func TestPaiseFormatsAsRupees(t *testing.T) {
	if Paise(48700).String() != "487.00" || Paise(5).String() != "0.05" || Paise(-4000).String() != "-40.00" {
		t.Error("format")
	}
	// Property: formatting then parsing gives the same paise.
	if err := quick.Check(func(n uint32) bool {
		p := Paise(n)
		back, ok := parsePaise(p.String())
		return ok && back == p
	}, nil); err != nil {
		t.Error(err)
	}
}

func TestLastAmountIsWhatTheLineCharges(t *testing.T) {
	cases := []struct {
		line string
		want amount
		ok   bool
	}{
		{"Delivery Fee ₹50.00 ₹35.00", amount{value: 3500}, true}, // struck through, then charged
		{"Delivery Fee ₹30 ₹0", amount{value: 0}, true},
		{"Delivery fee FREE", amount{free: true}, true},
		{"Delivery fee ₹30 FREE", amount{free: true}, true},
		{"Coupon discount -₹35.00", amount{value: -3500}, true},
		{"Discount ₹ -20", amount{value: -2000}, true},
		{"Rs. 1,299 paid", amount{value: 129900}, true},
		{"INR 45.5", amount{value: 4550}, true},
		{"Delivery fee", amount{}, false},
	}
	for _, c := range cases {
		got, ok := lastAmount(c.line)
		if ok != c.ok || got != c.want {
			t.Errorf("lastAmount(%q) = %+v, %v; want %+v, %v", c.line, got, ok, c.want, c.ok)
		}
	}
}

func TestFeeLabelsAreReadOnlyAtTheStartOfALine(t *testing.T) {
	fees := readFees([]string{"Free delivery on orders above ₹199", "Get a tip: order before ₹99"}, commonFeeLabels)
	if len(fees) != 0 {
		t.Errorf("promotional lines are not fees: %+v", fees)
	}
	fees = readFees([]string{"Packaging charge ₹10", "Carry bag ₹5", "Bag fee ₹3"}, commonFeeLabels)
	if len(fees) != 1 || fees[0] != (Fee{FeePackaging, 1800}) {
		t.Errorf("one kind sums its lines: %+v", fees)
	}
	fees = readFees([]string{"Delivery fee ₹20", "Delivery fee discount -₹40"}, commonFeeLabels)
	if len(fees) != 0 {
		t.Errorf("a fee never goes below zero: %+v", fees)
	}
}

func TestPaymentModes(t *testing.T) {
	cases := []struct {
		lines []string
		want  payment
	}{
		{[]string{"Paid via Amazon Pay Later"}, payment{mode: PayLater}},
		{[]string{"Paid via LazyPay"}, payment{mode: PayLater}},
		{[]string{"Payment mode: Pay on Delivery"}, payment{mode: PayCOD}},
		{[]string{"Paid via ICICI Amazon Pay Credit Card ₹500"}, payment{mode: PayCard, paid: 50000}},
		{[]string{"Paid using", "Google Pay"}, payment{mode: PayUPI}},
		{[]string{"Paid via Net Banking"}, payment{mode: PayNetbanking}},
		{[]string{"Paid via Zepto Cash ₹50", "Paid via UPI"}, payment{mode: PaySplit}}, // bank part unknown
		{[]string{"Paid via SuperCoins ₹20", "Paid via UPI ₹180"}, payment{mode: PaySplit, paid: 18000}},
		{[]string{"Paid via something new"}, payment{mode: PayUnknown}},
		{nil, payment{mode: PayUnknown}},
	}
	for _, c := range cases {
		if got := readPayment(c.lines); got != c.want {
			t.Errorf("readPayment(%q) = %+v, want %+v", c.lines, got, c.want)
		}
	}
}

func TestItemCounts(t *testing.T) {
	if countItems([]string{"Item count: 7", "3 items"}) != 7 {
		t.Error("an explicit count wins")
	}
	if countItems([]string{"Items ( 12 )"}) != 12 || countItems([]string{"1 item"}) != 1 || countItems([]string{"Thanks"}) != 0 {
		t.Error("counts")
	}
	if sumQuantities([]string{"Qty: 2", "Quantity - 3", "Qty 1"}) != 6 {
		t.Error("quantities add up")
	}
}

func TestOrderTimeFromTheBodyIsIndiaTimeAndNeverAfterTheEmail(t *testing.T) {
	header := time.Date(2026, 10, 17, 18, 35, 0, 0, time.UTC) // 00:05 IST on the 18th
	got := orderedAt([]string{"Order placed on: 17 Oct 2026 at 11:52 pm"}, header)
	if want := time.Date(2026, 10, 17, 18, 22, 0, 0, time.UTC); !got.Equal(want) {
		t.Errorf("got %v, want %v", got, want)
	}
	if got := orderedAt([]string{"Order date: 19 Oct 2026, 9:00 AM"}, header); !got.Equal(header) {
		t.Error("a body time after the email is a typo or a delivery slot: the header is used")
	}
	if got := orderedAt([]string{"Order date: 17 Oct 2026"}, header); !got.Equal(header) {
		t.Error("a date without a time: the header is more precise")
	}
	if got := orderedAt([]string{"Order date:  "}, time.Time{}); !got.IsZero() {
		t.Error("nothing known stays zero")
	}
	if got := orderedAt([]string{"Ordered on 02/10/2026 14:05"}, time.Time{}); !got.Equal(time.Date(2026, 10, 2, 14, 5, 0, 0, ist)) {
		t.Errorf("numeric dates are day first: %v", got)
	}
}

func TestRegistryPicksTheParserBySenderDomain(t *testing.T) {
	reg := Default()
	cases := map[string]string{
		"Swiggy <noreply@swiggy.in>":         "swiggy",
		"noreply@mailer.swiggy.in":           "swiggy",
		"Zomato <NoReply@Zomato.com>":        "zomato",
		"auto-confirm@amazon.in":             "amazon",
		"Flipkart <noreply@flipkart.com>":    "flipkart",
		"blinkit <noreply@blinkit.com>":      "blinkit",
		"Zepto Team <noreply@zeptonow.com>":  "zepto",
		"broken <noreply@grofers.com":        "blinkit",
		"HDFC Bank <alerts@hdfcbank.net>":    "",
		"noreply@notswiggy.in":               "",
		"no address at all":                  "",
		"Amazon Pay <no-reply@amazonpay.in>": "", // the wallet's mail is financial mail, not a receipt
	}
	for from, want := range cases {
		p := reg.ParserFor(from)
		got := ""
		if p != nil {
			got = p.Name()
		}
		if got != want {
			t.Errorf("ParserFor(%q) = %q, want %q", from, got, want)
		}
	}
	if _, err := NewRegistry(Swiggy(), Swiggy()); err == nil {
		t.Error("two parsers claiming one domain is refused")
	}
	if len(reg.Domains()) < 6 {
		t.Error("every app's domains are listed")
	}
}

func TestValidateRefusesInconsistentReceipts(t *testing.T) {
	base := Receipt{App: "Zepto", Kind: KindOrder, OrderID: "Z1", OrderedAt: time.Now(), Total: 10000, Payment: PayUPI}
	bad := []Receipt{
		func() Receipt { r := base; r.App = ""; return r }(),
		func() Receipt { r := base; r.OrderID = ""; return r }(),
		func() Receipt { r := base; r.OrderedAt = time.Time{}; return r }(),
		func() Receipt { r := base; r.Total = 0; return r }(),
		func() Receipt { r := base; r.ItemCount = -1; return r }(),
		func() Receipt { r := base; r.PaidAmount = 20000; return r }(),
		func() Receipt { r := base; r.Fees = []Fee{{FeeTip, 0}}; return r }(),
		func() Receipt { r := base; r.Fees = []Fee{{FeeTip, 10}, {FeeTip, 10}}; return r }(),
		func() Receipt { r := base; r.Fees = []Fee{{"mystery", 10}}; return r }(),
		func() Receipt { r := base; r.Kind = KindRefund; return r }(),
		func() Receipt { r := base; r.Kind = "other"; return r }(),
	}
	for i, r := range bad {
		if err := r.Validate(); !errors.Is(err, ErrInvalidReceipt) {
			t.Errorf("case %d: want ErrInvalidReceipt, got %v", i, err)
		}
	}
	if err := base.Validate(); err != nil {
		t.Errorf("a good receipt: %v", err)
	}
	if (Receipt{App: "Swiggy", Kind: KindCancellation, OrderID: "1", OrderedAt: time.Now()}).Validate() != nil {
		t.Error("a cancellation needs no total")
	}
}

func TestAnUnparsableRefundIsAFailureNotASkip(t *testing.T) {
	e := Email{From: "noreply@zomato.com", Subject: "Refund initiated", Body: "Order ID: 6655443322\nWe are processing it.", Date: time.Now()}
	if _, err := Zomato().Parse(e); !errors.Is(err, ErrInvalidReceipt) {
		t.Fatalf("want ErrInvalidReceipt, got %v", err)
	}
}
