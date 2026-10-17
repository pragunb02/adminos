package receipts

import (
	"bufio"
	"errors"
	"net/mail"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

// The fixtures in testdata are synthetic (AOS-903): written for these tests in the usual layout of
// each app's receipt. No real email, name or address is in the repository.

// loadFixture reads "From/Subject/Date" headers, a blank line, then the body.
func loadFixture(t *testing.T, name string) Email {
	t.Helper()
	f, err := os.Open(filepath.Join("testdata", name))
	if err != nil {
		t.Fatalf("open fixture: %v", err)
	}
	defer f.Close()
	var e Email
	var body strings.Builder
	inBody := false
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := sc.Text()
		if inBody {
			body.WriteString(line + "\n")
			continue
		}
		if line == "" {
			inBody = true
			continue
		}
		key, value, _ := strings.Cut(line, ": ")
		switch key {
		case "From":
			e.From = value
		case "Subject":
			e.Subject = value
		case "Date":
			if d, err := mail.ParseDate(value); err == nil {
				e.Date = d
			}
		}
	}
	e.MessageID = "msg-" + name
	e.Body = body.String()
	return e
}

func at(s string) time.Time {
	t, err := time.ParseInLocation("2006-01-02 15:04", s, ist)
	if err != nil {
		panic(err)
	}
	return t
}

func TestEveryFixtureParsesToItsExpectedReceipt(t *testing.T) {
	cases := []struct {
		fixture string
		want    Receipt
	}{
		{"swiggy_food_order.txt", Receipt{App: "Swiggy", Vertical: FoodDelivery, Kind: KindOrder, OrderID: "187654321098",
			OrderedAt: at("2026-10-17 21:40"), Total: 48700, ItemCount: 3, Payment: PayUPI, PaidAmount: 48700,
			Fees: []Fee{{FeeDelivery, 3500}, {FeePlatform, 1000}, {FeeSurge, 2500}, {FeePackaging, 2000}, {FeeTip, 2000}}}},
		{"swiggy_food_member.txt", Receipt{App: "Swiggy", Vertical: FoodDelivery, Kind: KindOrder, OrderID: "187654329876",
			OrderedAt: at("2026-10-18 13:05"), Total: 45000, ItemCount: 2, Payment: PaySplit, PaidAmount: 35000,
			Fees: []Fee{{FeePlatform, 1000}}}},
		{"swiggy_instamart_order.txt", Receipt{App: "Swiggy Instamart", Vertical: QuickCommerce, Kind: KindOrder, OrderID: "INS7766554433",
			OrderedAt: at("2026-10-19 08:12"), Total: 18980, ItemCount: 4, Payment: PayCard, PaidAmount: 18980,
			Fees: []Fee{{FeeDelivery, 3000}, {FeeHandling, 980}, {FeeSmallCart, 2500}}}},
		{"swiggy_cancelled.txt", Receipt{App: "Swiggy", Vertical: FoodDelivery, Kind: KindCancellation, OrderID: "187654321098",
			OrderedAt: at("2026-10-17 22:10"), Payment: PayUnknown}},
		{"zomato_order.txt", Receipt{App: "Zomato", Vertical: FoodDelivery, Kind: KindOrder, OrderID: "6655443322",
			OrderedAt: at("2026-10-16 23:48"), Total: 74550, ItemCount: 2, Payment: PayWallet,
			Fees: []Fee{{FeePlatform, 1000}, {FeeSurge, 1500}, {FeePackaging, 3000}, {FeeTip, 3000}}}},
		{"zomato_refund.txt", Receipt{App: "Zomato", Vertical: FoodDelivery, Kind: KindRefund, OrderID: "6655443322",
			OrderedAt: at("2026-10-17 10:00"), Payment: PayUnknown, RefundAmount: 12000}},
		{"zomato_refund_credited.txt", Receipt{App: "Zomato", Vertical: FoodDelivery, Kind: KindRefund, OrderID: "6655443322",
			OrderedAt: at("2026-10-19 10:00"), Payment: PayUnknown, RefundAmount: 12000}},
		{"zepto_order.txt", Receipt{App: "Zepto", Vertical: QuickCommerce, Kind: KindOrder, OrderID: "ZPT-AB12CD34",
			OrderedAt: at("2026-10-14 07:30"), Total: 21300, ItemCount: 6, Payment: PayLater,
			Fees: []Fee{{FeeHandling, 1300}, {FeeSmallCart, 3500}, {FeeSurge, 1000}}}},
		{"blinkit_order.txt", Receipt{App: "Blinkit", Vertical: QuickCommerce, Kind: KindOrder, OrderID: "4433221100",
			OrderedAt: at("2026-10-17 23:52"), Total: 22000, ItemCount: 3, Payment: PayCOD,
			Fees: []Fee{{FeeDelivery, 2500}, {FeeHandling, 400}, {FeeSmallCart, 2000}, {FeeSurge, 1500}}}},
		{"amazon_order.txt", Receipt{App: "Amazon", Vertical: Ecommerce, Kind: KindOrder, OrderID: "402-1234567-7654321",
			OrderedAt: at("2026-10-15 14:20"), Total: 132900, ItemCount: 3, Payment: PayCard, PaidAmount: 132900,
			Fees: []Fee{{FeeDelivery, 4000}}}},
		{"amazon_split.txt", Receipt{App: "Amazon", Vertical: Ecommerce, Kind: KindOrder, OrderID: "403-7654321-1234567",
			OrderedAt: at("2026-10-15 18:20"), Total: 99900, ItemCount: 1, Payment: PaySplit, PaidAmount: 79900, Fees: []Fee{}}},
		{"flipkart_order.txt", Receipt{App: "Flipkart", Vertical: Ecommerce, Kind: KindOrder, OrderID: "OD12345678901234567",
			OrderedAt: at("2026-10-13 20:15"), Total: 107100, ItemCount: 1, Payment: PayLater,
			Fees: []Fee{{FeePlatform, 300}, {FeePackaging, 1900}}}},
		{"flipkart_minutes.txt", Receipt{App: "Flipkart Minutes", Vertical: QuickCommerce, Kind: KindOrder, OrderID: "OD99887766554433221",
			OrderedAt: at("2026-10-13 21:15"), Total: 31200, ItemCount: 5, Payment: PayUPI, PaidAmount: 31200,
			Fees: []Fee{{FeeDelivery, 2500}, {FeeHandling, 1500}}}},
	}
	reg := Default()
	for _, c := range cases {
		t.Run(c.fixture, func(t *testing.T) {
			e := loadFixture(t, c.fixture)
			p := reg.ParserFor(e.From)
			if p == nil {
				t.Fatalf("no parser for the sender")
			}
			got, err := p.Parse(e)
			if err != nil {
				t.Fatalf("parse: %v", err)
			}
			if c.want.Fees == nil && c.want.Kind == KindOrder {
				c.want.Fees = []Fee{}
			}
			if !got.OrderedAt.Equal(c.want.OrderedAt) {
				t.Errorf("orderedAt = %v, want %v", got.OrderedAt, c.want.OrderedAt)
			}
			got.OrderedAt, c.want.OrderedAt = time.Time{}, time.Time{}
			if !reflect.DeepEqual(got, c.want) {
				t.Errorf("got  %+v\nwant %+v", got, c.want)
			}
		})
	}
}

func TestEmailsThatAreNotReceiptsAreSkipped(t *testing.T) {
	reg := Default()
	for _, fixture := range []string{"swiggy_promo.txt", "zomato_out_for_delivery.txt", "amazon_shipped.txt"} {
		e := loadFixture(t, fixture)
		if _, err := reg.ParserFor(e.From).Parse(e); !errors.Is(err, ErrNotAReceipt) {
			t.Errorf("%s: want ErrNotAReceipt, got %v", fixture, err)
		}
	}
}

func TestAReceiptWhoseFeesExceedItsTotalIsRefused(t *testing.T) {
	e := loadFixture(t, "zepto_bad_total.txt")
	if _, err := Default().ParserFor(e.From).Parse(e); !errors.Is(err, ErrInvalidReceipt) {
		t.Fatalf("want ErrInvalidReceipt, got %v", err)
	}
}

func TestEveryEmailAboutOneOrderHasTheSameKey(t *testing.T) {
	reg := Default()
	parse := func(name string) Receipt {
		e := loadFixture(t, name)
		r, err := reg.ParserFor(e.From).Parse(e)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		return r
	}
	order, cancel := parse("swiggy_food_order.txt"), parse("swiggy_cancelled.txt")
	if order.OrderRefHash() != cancel.OrderRefHash() {
		t.Error("the cancellation must land on the order's row")
	}
	if len(order.OrderRefHash()) != 64 || strings.Contains(order.OrderRefHash(), order.OrderID) {
		t.Error("the key is a SHA-256 hex digest, never the order number")
	}
	refund1, refund2 := parse("zomato_refund.txt"), parse("zomato_refund_credited.txt")
	if refund1.OrderRefHash() != refund2.OrderRefHash() {
		t.Error("two refund emails for one order share its key")
	}
	other := Receipt{App: "Zomato", OrderID: "187654321098"}
	if other.OrderRefHash() == order.OrderRefHash() {
		t.Error("the same number at another app is another order")
	}
	if (Receipt{App: "Swiggy", OrderID: " 187654321098 "}).OrderRefHash() != order.OrderRefHash() {
		t.Error("spaces around the order number don't change the key")
	}
}
