// Package receipts reads order-receipt emails from quick-commerce, food-delivery and e-commerce apps
// (AOS-903): the order total, item count and the fees on top of the items. One Parser per sender
// (Strategy), chosen by the sender's domain (Registry).
//
// Privacy (D-33): an Email lives in memory only. A Receipt keeps structured fields: no item names,
// no address, and the order ID only until it is hashed (OrderRefHash) for storage. Neither is logged.
package receipts

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Vertical is the kind of app, for the habit lenses (AOS-901).
type Vertical string

const (
	QuickCommerce Vertical = "quick_commerce"
	FoodDelivery  Vertical = "food_delivery"
	Ecommerce     Vertical = "ecommerce"
)

// Kind is what an email says about an order.
type Kind string

const (
	KindOrder        Kind = "order"
	KindCancellation Kind = "cancellation"
	KindRefund       Kind = "refund"
)

// FeeKind is a charge on top of the items. The same closed set is in the API (FeeKind) and in the
// order_receipt_fees CHECK constraint (V38).
type FeeKind string

const (
	FeeDelivery  FeeKind = "delivery"
	FeeHandling  FeeKind = "handling"
	FeeSmallCart FeeKind = "small_cart"
	FeePlatform  FeeKind = "platform"
	FeeSurge     FeeKind = "surge" // surge, rain, peak-hour, late-night and high-demand fees
	FeePackaging FeeKind = "packaging"
	FeeTip       FeeKind = "tip"
)

// FeeKinds lists every FeeKind, in display order.
var FeeKinds = []FeeKind{FeeDelivery, FeeHandling, FeeSmallCart, FeePlatform, FeeSurge, FeePackaging, FeeTip}

// PaymentMode is how the order was paid, as the receipt says. Wallet, pay-later and cash on delivery
// mean no bank payment is expected at order time; split means part wallet, part bank.
type PaymentMode string

const (
	PayUPI        PaymentMode = "upi"
	PayCard       PaymentMode = "card"
	PayNetbanking PaymentMode = "netbanking"
	PayWallet     PaymentMode = "wallet"
	PayLater      PaymentMode = "pay_later"
	PayCOD        PaymentMode = "cod"
	PaySplit      PaymentMode = "split"
	PayUnknown    PaymentMode = "unknown"
)

// Paise is an exact amount of money in paise (₹1 = 100). Never a float (docs/ENGINEERING.md §2).
type Paise int64

// String formats paise as rupees with two decimals, e.g. 12345 → "123.45", for NUMERIC columns.
func (p Paise) String() string {
	sign := ""
	if p < 0 {
		sign, p = "-", -p
	}
	return fmt.Sprintf("%s%d.%02d", sign, p/100, p%100)
}

// Fee is one kind of fee and what was actually charged for it.
type Fee struct {
	Kind   FeeKind
	Amount Paise
}

// Email is what a parser reads. It lives in memory only (D-33).
type Email struct {
	MessageID string
	From      string
	Subject   string
	Body      string
	// Date is the email's Date header; zero when it couldn't be read.
	Date time.Time
}

// Receipt is the structured part of an order email: everything that is stored, and nothing else.
type Receipt struct {
	App      string // the merchant-catalogue brand, e.g. "Swiggy Instamart"
	Vertical Vertical
	Kind     Kind
	// OrderID is the order number as printed. It is only used to compute OrderRefHash; never stored or logged.
	OrderID   string
	OrderedAt time.Time
	// Total is what the order cost in all (items + fees + taxes − discounts); 0 when the email doesn't say.
	Total Paise
	// ItemCount is the number of items; 0 when the email doesn't say.
	ItemCount int
	// Fees has at most one entry per kind, in FeeKinds order; waived fees are left out.
	Fees    []Fee
	Payment PaymentMode
	// PaidAmount is what was charged to the bank, card or UPI when the email shows it (e.g. a split
	// payment's non-wallet part); 0 when it doesn't.
	PaidAmount Paise
	// RefundAmount is the refund an email announces (KindRefund only).
	RefundAmount Paise
}

// Bounds a receipt must stay within: anything larger is a hostile or mis-read email, refused.
const (
	MaxOrderTotal Paise = 50_00_000_00 // ₹50 lakh
	MaxItems            = 500
)

var (
	// ErrNotAReceipt means the email is from a receipt sender but isn't a receipt we read (a promotion,
	// a "your order is on its way" update without amounts). It is skipped, not a failure.
	ErrNotAReceipt = errors.New("not an order receipt")
	// ErrInvalidReceipt means the email looked like a receipt but its numbers don't add up. It is
	// counted as a failure, so a changed format shows up instead of storing wrong fees.
	ErrInvalidReceipt = errors.New("invalid order receipt")
)

// Validate checks the receipt is consistent before it is stored.
func (r Receipt) Validate() error {
	switch {
	case r.App == "":
		return fmt.Errorf("%w: no app", ErrInvalidReceipt)
	case r.OrderID == "":
		return fmt.Errorf("%w: no order ID", ErrInvalidReceipt)
	case r.OrderedAt.IsZero():
		return fmt.Errorf("%w: no order time", ErrInvalidReceipt)
	case r.Total < 0 || r.PaidAmount < 0 || r.RefundAmount < 0 || r.ItemCount < 0:
		return fmt.Errorf("%w: negative amount", ErrInvalidReceipt)
	case r.Total > MaxOrderTotal || r.RefundAmount > MaxOrderTotal || r.ItemCount > MaxItems:
		return fmt.Errorf("%w: implausibly large", ErrInvalidReceipt)
	}
	switch r.Kind {
	case KindOrder:
		if r.Total <= 0 {
			return fmt.Errorf("%w: an order needs a total", ErrInvalidReceipt)
		}
		if r.PaidAmount > r.Total {
			return fmt.Errorf("%w: paid more than the total", ErrInvalidReceipt)
		}
		if r.FeeTotal() > r.Total {
			return fmt.Errorf("%w: fees exceed the total", ErrInvalidReceipt)
		}
	case KindRefund:
		if r.RefundAmount <= 0 {
			return fmt.Errorf("%w: a refund needs an amount", ErrInvalidReceipt)
		}
	case KindCancellation:
	default:
		return fmt.Errorf("%w: unknown kind %q", ErrInvalidReceipt, r.Kind)
	}
	seen := map[FeeKind]bool{}
	for _, f := range r.Fees {
		if f.Amount <= 0 || seen[f.Kind] || !knownFee(f.Kind) {
			return fmt.Errorf("%w: bad fee %q", ErrInvalidReceipt, f.Kind)
		}
		seen[f.Kind] = true
	}
	return nil
}

// FeeTotal is the sum of every fee, tips included.
func (r Receipt) FeeTotal() Paise {
	var sum Paise
	for _, f := range r.Fees {
		sum += f.Amount
	}
	return sum
}

// OrderRefHash is the stored key of an order: SHA-256 of the app and the normalised order ID, so
// every email about one order lands on the same row without keeping the order number (D-33).
func (r Receipt) OrderRefHash() string {
	sum := sha256.Sum256([]byte(strings.ToLower(r.App) + ":" + strings.ToUpper(strings.TrimSpace(r.OrderID))))
	return hex.EncodeToString(sum[:])
}

func knownFee(k FeeKind) bool {
	for _, f := range FeeKinds {
		if f == k {
			return true
		}
	}
	return false
}

// sortedFees turns per-kind sums into Fees in FeeKinds order, leaving out kinds that net to nothing.
func sortedFees(sums map[FeeKind]Paise) []Fee {
	fees := make([]Fee, 0, len(sums))
	for _, k := range FeeKinds {
		if amount := sums[k]; amount > 0 {
			fees = append(fees, Fee{Kind: k, Amount: amount})
		}
	}
	return fees
}
