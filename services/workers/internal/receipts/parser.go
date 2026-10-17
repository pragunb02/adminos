package receipts

import (
	"fmt"
	"net/mail"
	"regexp"
	"strings"
)

// Parser reads one sender's receipt emails (Strategy). A new app is a new Parser in the registry.
type Parser interface {
	// Name identifies the parser in logs and counts (never the email's content).
	Name() string
	// Domains are the sender domains this parser reads; a subdomain matches too.
	Domains() []string
	// Parse reads the email. ErrNotAReceipt means "skip"; ErrInvalidReceipt means the numbers don't add up.
	Parse(e Email) (Receipt, error)
}

// Registry picks the parser for an email's sender (Registry pattern).
type Registry struct {
	parsers []Parser
}

// NewRegistry builds a registry; two parsers may not claim the same domain.
func NewRegistry(parsers ...Parser) (*Registry, error) {
	owner := map[string]string{}
	for _, p := range parsers {
		for _, d := range p.Domains() {
			d = strings.ToLower(d)
			if other, taken := owner[d]; taken {
				return nil, fmt.Errorf("receipt domain %s claimed by both %s and %s", d, other, p.Name())
			}
			owner[d] = p.Name()
		}
	}
	return &Registry{parsers: parsers}, nil
}

// Default is the registry of every supported app.
func Default() *Registry {
	r, err := NewRegistry(Swiggy(), Zomato(), Zepto(), Blinkit(), Amazon(), Flipkart())
	if err != nil {
		panic(err) // a programming error in the static list, caught by the tests
	}
	return r
}

// Domains lists every domain the registry reads, sorted as registered.
func (r *Registry) Domains() []string {
	var out []string
	for _, p := range r.parsers {
		out = append(out, p.Domains()...)
	}
	return out
}

// ParserFor returns the parser for a From header ("Swiggy <noreply@swiggy.in>"), or nil when the
// sender isn't a receipt sender.
func (r *Registry) ParserFor(from string) Parser {
	domain := senderDomain(from)
	if domain == "" {
		return nil
	}
	for _, p := range r.parsers {
		for _, d := range p.Domains() {
			d = strings.ToLower(d)
			if domain == d || strings.HasSuffix(domain, "."+d) {
				return p
			}
		}
	}
	return nil
}

func senderDomain(from string) string {
	addr := from
	if a, err := mail.ParseAddress(from); err == nil {
		addr = a.Address
	} else if l, r := strings.LastIndexByte(from, '<'), strings.LastIndexByte(from, '>'); l >= 0 && r > l {
		addr = from[l+1 : r]
	}
	at := strings.LastIndexByte(addr, '@')
	if at < 0 {
		return ""
	}
	return strings.ToLower(strings.TrimSpace(addr[at+1:]))
}

// layout describes one app's receipts for the shared engine. Each app's file builds one and may
// override a step (e.g. Swiggy decides Instamart vs food; Amazon sums quantities).
type layout struct {
	name     string
	app      string
	vertical Vertical
	domains  []string
	// orderID finds the order number (group 1).
	orderID *regexp.Regexp
	// totals are the labels of the order total, preferred first. "Item total" is never one.
	totals []*regexp.Regexp
	// refunds are the labels of a refund amount.
	refunds []*regexp.Regexp
	fees    []feeLabel
	// items counts the items; nil uses countItems.
	items func(lines []string) int
	// classify decides the app and vertical for one email; nil keeps app and vertical.
	classify func(subject string, lines []string) (app string, vertical Vertical)
}

func (l layout) Name() string      { return l.name }
func (l layout) Domains() []string { return l.domains }

var (
	cancelledSubject = regexp.MustCompile(`(?i)\bcancel(?:l?ed|lation)\b`)
	refundSubject    = regexp.MustCompile(`(?i)\brefund`)
	orderSubject     = regexp.MustCompile(`(?i)\border\b|\bdelivered\b|\breceipt\b|\binvoice\b|\bsummary\b|\bbill\b`)
	// promoSubject marks marketing with order words ("Order now", "% off") so it is never read as a receipt.
	promoSubject = regexp.MustCompile(`(?i)\border\s+now\b|\b\d{1,2}\s*%\s*off\b|\bflat\s+₹?\d+\s+off\b|\bdeal|\bsale\b|\bcoupon\b`)
)

func kindOf(subject string) (Kind, bool) {
	switch {
	case promoSubject.MatchString(subject):
		return "", false
	case cancelledSubject.MatchString(subject):
		return KindCancellation, true
	case refundSubject.MatchString(subject):
		return KindRefund, true
	case orderSubject.MatchString(subject):
		return KindOrder, true
	default:
		return "", false
	}
}

func (l layout) Parse(e Email) (Receipt, error) {
	subject := invisible.Replace(e.Subject)
	kind, ok := kindOf(subject)
	if !ok {
		return Receipt{}, ErrNotAReceipt
	}
	lines := splitLines(e.Body)
	id := l.orderID.FindStringSubmatch(subject + "\n" + strings.Join(lines, "\n"))
	if id == nil {
		return Receipt{}, ErrNotAReceipt // "your order is on its way" without a number: nothing to keep
	}
	r := Receipt{App: l.app, Vertical: l.vertical, Kind: kind, OrderID: id[1], OrderedAt: orderedAt(lines, e.Date), Payment: PayUnknown}
	if l.classify != nil {
		r.App, r.Vertical = l.classify(subject, lines)
	}
	switch kind {
	case KindOrder:
		total, ok := labelledAmount(lines, l.totals)
		if !ok || total.free || total.value <= 0 {
			return Receipt{}, ErrNotAReceipt // a status update ("out for delivery") with no bill
		}
		r.Total = total.value
		r.Fees = readFees(lines, l.fees)
		r.ItemCount = l.countItems(lines)
		p := readPayment(lines)
		r.Payment, r.PaidAmount = p.mode, p.paid
		if r.Payment != PaySplit && isBankMode(r.Payment) && r.PaidAmount == 0 {
			r.PaidAmount = r.Total
		}
	case KindRefund:
		refund, ok := labelledAmount(lines, l.refunds)
		if !ok || refund.free || refund.value <= 0 {
			return Receipt{}, fmt.Errorf("%w: refund without an amount", ErrInvalidReceipt)
		}
		r.RefundAmount = refund.value
	}
	if err := r.Validate(); err != nil {
		return Receipt{}, err
	}
	return r, nil
}

func (l layout) countItems(lines []string) int {
	if l.items != nil {
		return l.items(lines)
	}
	return countItems(lines)
}

// Labels shared by most apps.
var (
	genericOrderID = regexp.MustCompile(`(?i)\border\s*(?:id|no\.?|number|#)\s*[:#.\-]?\s*#?\s*([A-Z0-9][A-Z0-9\-]{5,29})\b`)
	commonTotals   = labels("grand total", "order total", "total paid", "amount paid", "bill total", "total amount", "total payable", "to pay")
	commonRefunds  = labels("refund amount", "amount refunded", "refund of", "refunded amount", "refund")
)
