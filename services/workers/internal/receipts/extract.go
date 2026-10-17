package receipts

import (
	"regexp"
	"strconv"
	"strings"
	"time"
)

// The shared label-line engine. Receipts are tables: a label ("Delivery fee"), then the amount on the
// same line or the next one (HTML cells collapse onto one line; rows break lines). Parsers describe
// their labels; these helpers read them. Labels are anchored at the start of a line, so promotional
// text ("Free delivery on orders above ₹199") never reads as a fee.

var (
	// amountRE is a rupee amount: ₹ / Rs / INR, an optional minus (a discount), digits with Indian
	// grouping, and up to two decimals.
	amountRE = regexp.MustCompile(`(?i)(-|−)?\s*(?:₹|rs\.?|inr)\s*(-|−)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)`)
	freeRE   = regexp.MustCompile(`(?i)\bfree\b|\bwaived\b`)
)

// amount is one money value read from a line.
type amount struct {
	value Paise
	free  bool // "FREE" or "waived": charged nothing
}

// lastAmount reads what a line actually charges: the last amount on it (a struck-through "₹30 ₹0"
// charged ₹0), negative for discounts; or FREE. ok is false when the line has no amount.
func lastAmount(line string) (amount, bool) {
	matches := amountRE.FindAllStringSubmatch(line, -1)
	if len(matches) == 0 {
		if freeRE.MatchString(line) {
			return amount{free: true}, true
		}
		return amount{}, false
	}
	m := matches[len(matches)-1]
	p, ok := parsePaise(m[3])
	if !ok {
		return amount{}, false
	}
	if m[1] != "" || m[2] != "" {
		p = -p
	}
	// "₹30 FREE": the word after the last amount wins.
	if idx := amountRE.FindAllStringIndex(line, -1); freeRE.MatchString(line[idx[len(idx)-1][1]:]) {
		return amount{free: true}, true
	}
	return amount{value: p}, true
}

// parsePaise reads "1,234.5" as 123450 paise. Grouping commas may be Indian (1,23,456) or not.
func parsePaise(s string) (Paise, bool) {
	s = strings.ReplaceAll(s, ",", "")
	rupees, frac, _ := strings.Cut(s, ".")
	r, err := strconv.ParseInt(rupees, 10, 64)
	if err != nil || r > 1e9 { // ₹100 crore is not an order receipt
		return 0, false
	}
	var f int64
	switch len(frac) {
	case 0:
	case 1:
		f, _ = strconv.ParseInt(frac, 10, 64)
		f *= 10
	default:
		f, _ = strconv.ParseInt(frac[:2], 10, 64)
	}
	return Paise(r*100 + f), true
}

// labels compiles label phrases into patterns anchored at the start of a line, case-insensitive,
// with flexible spacing ("Delivery  fee", "Delivery Fee:").
func labels(phrases ...string) []*regexp.Regexp {
	out := make([]*regexp.Regexp, len(phrases))
	for i, p := range phrases {
		words := strings.Fields(regexp.QuoteMeta(p))
		out[i] = regexp.MustCompile(`(?i)^\W*` + strings.Join(words, `\s+`) + `\b`)
	}
	return out
}

func startsWithAny(line string, res []*regexp.Regexp) bool {
	for _, re := range res {
		if re.MatchString(line) {
			return true
		}
	}
	return false
}

// labelledAmount is the amount on the first line that starts with one of labels (tried in order, so
// the preferred label wins), or on the next line when the label's line has none.
func labelledAmount(lines []string, res []*regexp.Regexp) (amount, bool) {
	for _, re := range res {
		for i, line := range lines {
			if !re.MatchString(line) {
				continue
			}
			if a, ok := amountOnOrAfter(lines, i); ok {
				return a, true
			}
		}
	}
	return amount{}, false
}

// amountOnOrAfter is the amount on line i, or on line i+1 when that line is only an amount.
func amountOnOrAfter(lines []string, i int) (amount, bool) {
	if a, ok := lastAmount(lines[i]); ok {
		return a, true
	}
	if i+1 < len(lines) && amountOnlyLine.MatchString(lines[i+1]) {
		return lastAmount(lines[i+1])
	}
	return amount{}, false
}

var amountOnlyLine = regexp.MustCompile(`(?i)^\s*(?:[-−]?\s*(?:₹|rs\.?|inr)\s*[-−]?\s*[0-9][0-9,]*(?:\.[0-9]{1,2})?\s*)+$|^\s*(?:free|waived)\s*$`)

// feeLabel ties label phrases to a fee kind.
type feeLabel struct {
	kind FeeKind
	res  []*regexp.Regexp
}

// commonFeeLabels are the fee names the apps use. More specific names come first: "Delivery partner
// tip" is a tip, not a delivery fee.
var commonFeeLabels = []feeLabel{
	{FeeTip, labels("delivery partner tip", "tip for delivery partner", "tip for your delivery partner", "rider tip", "tip")},
	{FeeSmallCart, labels("small cart fee", "small cart charge", "small order fee", "low cart fee", "small cart surcharge")},
	{FeeSurge, labels("surge fee", "surge charge", "rain fee", "rain surge", "peak hour fee", "late night fee", "late night charge", "high demand fee", "high demand surcharge", "bad weather fee")},
	{FeeHandling, labels("handling fee", "handling charge", "processing fee")},
	{FeePlatform, labels("platform fee", "convenience fee", "marketplace fee")},
	{FeePackaging, labels("packaging fee", "packaging charge", "packaging charges", "restaurant packaging", "secured packaging fee", "bag fee", "carry bag")},
	{FeeDelivery, labels("delivery fee", "delivery charge", "delivery charges", "shipping fee", "shipping charges", "shipping", "long distance fee")},
}

// readFees sums each fee kind over the lines that name it. A negative amount on a fee line (a
// member's "Delivery fee discount −₹40") reduces that kind; a kind never goes below zero.
func readFees(lines []string, table []feeLabel) []Fee {
	sums := map[FeeKind]Paise{}
	for i, line := range lines {
		for _, fl := range table {
			if !startsWithAny(line, fl.res) {
				continue
			}
			if a, ok := amountOnOrAfter(lines, i); ok && !a.free {
				sums[fl.kind] += a.value
			}
			break
		}
	}
	for k, v := range sums {
		if v < 0 {
			sums[k] = 0
		}
	}
	return sortedFees(sums)
}

// Payment-mode words, most specific first: "Amazon Pay Later" is pay-later, not the Amazon Pay
// wallet; "ICICI Amazon Pay credit card" is a card.
var paymentWords = []struct {
	mode PaymentMode
	re   *regexp.Regexp
}{
	{PayLater, regexp.MustCompile(`(?i)pay\s*later|\bsimpl\b|lazypay|zestmoney|\bpostpaid\b`)},
	{PayCOD, regexp.MustCompile(`(?i)cash\s+on\s+delivery|pay\s+on\s+delivery|\bcod\b`)},
	{PayCard, regexp.MustCompile(`(?i)credit\s+card|debit\s+card|\bvisa\b|mastercard|rupay\s+card|\bamex\b|\bcard\b`)},
	{PayWallet, regexp.MustCompile(`(?i)wallet|pay\s+balance|\bmoney\b|supercoins?|gift\s+card|\bcash\b|\bcredits?\b`)},
	{PayUPI, regexp.MustCompile(`(?i)\bupi\b|google\s*pay|\bgpay\b|phonepe|\bbhim\b`)},
	{PayNetbanking, regexp.MustCompile(`(?i)net\s*banking`)},
}

var paymentLineRE = regexp.MustCompile(`(?i)^\W*(?:paid\s+(?:via|using|by|with|from|through)|payment\s+(?:method|mode)|mode\s+of\s+payment|paid\s*:)\s*[:\-]?\s*(.*)$`)

// payment is how an order was paid and what reached the bank (0 when unknown).
type payment struct {
	mode PaymentMode
	paid Paise
}

// readPayment reads the "Paid via …" lines. One method is that method. A wallet (or coins) plus a
// bank method is a split, and what was paid by the bank method is the bank's part.
func readPayment(lines []string) payment {
	var modes []PaymentMode
	var bankPaid Paise
	bankAmountKnown := true
	for i, line := range lines {
		m := paymentLineRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		rest := m[1]
		if strings.TrimSpace(rest) == "" && i+1 < len(lines) {
			rest = lines[i+1]
		}
		mode := modeOf(rest)
		if mode == PayUnknown {
			continue
		}
		modes = append(modes, mode)
		if isBankMode(mode) {
			if a, ok := lastAmount(rest); ok && !a.free && a.value > 0 {
				bankPaid += a.value
			} else {
				bankAmountKnown = false
			}
		}
	}
	return combine(modes, bankPaid, bankAmountKnown)
}

func combine(modes []PaymentMode, bankPaid Paise, bankAmountKnown bool) payment {
	if len(modes) == 0 {
		return payment{mode: PayUnknown}
	}
	bank, other := 0, 0
	first := modes[0]
	for _, m := range modes {
		if isBankMode(m) {
			bank++
		} else {
			other++
		}
	}
	switch {
	case bank > 0 && other > 0:
		if !bankAmountKnown {
			bankPaid = 0
		}
		return payment{mode: PaySplit, paid: bankPaid}
	case bank > 0:
		if !bankAmountKnown {
			bankPaid = 0
		}
		return payment{mode: firstBankMode(modes), paid: bankPaid}
	default:
		return payment{mode: first}
	}
}

func firstBankMode(modes []PaymentMode) PaymentMode {
	for _, m := range modes {
		if isBankMode(m) {
			return m
		}
	}
	return PayUnknown
}

func modeOf(text string) PaymentMode {
	for _, w := range paymentWords {
		if w.re.MatchString(text) {
			return w.mode
		}
	}
	return PayUnknown
}

// isBankMode: money that leaves a bank account or card at order time, so a bank record is expected.
func isBankMode(m PaymentMode) bool {
	return m == PayUPI || m == PayCard || m == PayNetbanking
}

// itemCounters read the number of items; the first that finds one wins.
var (
	itemsInParens = regexp.MustCompile(`(?i)\bitems?\s*\(\s*(\d{1,3})\s*\)`)
	nItems        = regexp.MustCompile(`(?i)\b(\d{1,3})\s+items?\b`)
	itemCountLine = regexp.MustCompile(`(?i)^\W*(?:item\s+count|no\.?\s+of\s+items|number\s+of\s+items|total\s+items)\s*[:\-]?\s*(\d{1,3})\b`)
	quantityLine  = regexp.MustCompile(`(?i)^\W*(?:qty|quantity)\s*[:\-]?\s*(\d{1,3})\b`)
)

// countItems reads "Item count: 3", "Items (3)" or "3 items", in that order of trust.
func countItems(lines []string) int {
	for _, re := range []*regexp.Regexp{itemCountLine, itemsInParens, nItems} {
		for _, line := range lines {
			if m := re.FindStringSubmatch(line); m != nil {
				if n, err := strconv.Atoi(m[1]); err == nil && n > 0 {
					return n
				}
			}
		}
	}
	return 0
}

// sumQuantities adds up "Qty: N" lines (one per product line, as e-commerce receipts list them).
func sumQuantities(lines []string) int {
	total := 0
	for _, line := range lines {
		if m := quantityLine.FindStringSubmatch(line); m != nil {
			if n, err := strconv.Atoi(m[1]); err == nil {
				total += n
			}
		}
	}
	return total
}

// India time: the apps print local times without a zone.
var ist = time.FixedZone("IST", 5*60*60+30*60)

var orderedAtLabel = regexp.MustCompile(`(?i)^\W*(?:order\s+placed\s+on|ordered\s+on|order\s+placed\s+at|order\s+date(?:\s+and\s+time)?|order\s+time|placed\s+on)\s*[:\-]?\s*(.+)$`)

var dateTimeLayouts = []string{
	"2 Jan 2006, 3:04 PM", "2 Jan 2006 3:04 PM", "2 Jan 2006, 15:04", "2 Jan 2006 15:04",
	"02 Jan 2006, 03:04 PM", "Jan 2, 2006, 3:04 PM", "Jan 2, 2006 3:04 PM",
	"2 January 2006, 3:04 PM", "2 January 2006 3:04 PM", "Mon, 2 Jan 2006, 3:04 PM", "Mon, 2 Jan 2006 3:04 PM",
	"02/01/2006 15:04", "02/01/2006, 15:04", "02/01/2006 3:04 PM", "02-01-2006 15:04", "2006-01-02 15:04",
}

// orderedAt is when the order was placed: a date and time printed in the body (India time), if it
// is no later than the email itself; otherwise the email's Date header. A date without a time
// isn't used: the header is more precise. Zero when neither is known.
func orderedAt(lines []string, header time.Time) time.Time {
	for _, line := range lines {
		m := orderedAtLabel.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		raw := strings.Join(strings.Fields(strings.ReplaceAll(m[1], " at ", " ")), " ")
		if raw == "" {
			continue
		}
		raw = strings.NewReplacer(" am", " AM", " pm", " PM").Replace(raw)
		for _, layout := range dateTimeLayouts {
			if t, err := time.ParseInLocation(layout, raw, ist); err == nil {
				if header.IsZero() || !t.After(header.Add(5*time.Minute)) {
					return t
				}
			}
		}
	}
	return header
}

// Bodies longer than this are cut before parsing (the Gmail body is already capped at 64 KB); a
// receipt is a few hundred lines at most.
const maxLines = 2000

// invisible are zero-width and bidi-control characters. An email can hide them inside an amount
// ("₹4<ZWSP>65") or a label to make it read differently; they are removed before reading.
var invisible = strings.NewReplacer(
	"\u200b", "", "\u200c", "", "\u200d", "", "\u2060", "", "\ufeff", "", "\u00ad", "",
	"\u202a", "", "\u202b", "", "\u202c", "", "\u202d", "", "\u202e", "",
	"\u2066", "", "\u2067", "", "\u2068", "", "\u2069", "", "\u200e", "", "\u200f", "",
)

// splitLines returns the body's non-empty, trimmed lines, without invisible characters, at most maxLines.
// Digits are ASCII only ([0-9] in every pattern): look-alike digits (full-width, Devanagari) never read
// as an amount, so such a receipt fails closed instead of storing a wrong number.
func splitLines(body string) []string {
	raw := strings.Split(invisible.Replace(body), "\n")
	out := make([]string, 0, min(len(raw), maxLines))
	for _, l := range raw {
		if l = strings.TrimSpace(l); l != "" {
			out = append(out, l)
			if len(out) == maxLines {
				break
			}
		}
	}
	return out
}
