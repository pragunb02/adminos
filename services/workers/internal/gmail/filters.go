package gmail

import (
	"fmt"
	"io"
	"mime"
	"net/mail"
	"regexp"
	"strings"
)

// Financial senders (AOS-401, D-59; AOS-624): only mail from these senders is ever fetched in full,
// so personal mail is never read. This is data: adding a bank or biller is one line here.
//
//   - a Domain matches the sender's domain or any subdomain ("hdfcbank.net" matches
//     alerts@hdfcbank.net and x@mail.hdfcbank.net); the listed domain is the organisation;
//   - a SuffixDomain is a registry that only regulated banks can register under ("bank.in"): it
//     matches <bank>.bank.in and its subdomains, and each <bank>.bank.in is its own organisation;
//   - an Address matches exactly; its domain is the organisation.
//
// A sender match only makes a message a candidate: Qualify (authn.go) also requires Gmail to have
// authenticated that organisation. The subject never admits a message (AUD-11).
var (
	FinancialDomains = []string{
		// Banks and card issuers
		"hdfcbank.net", "hdfcbank.com",
		"icicibank.com",
		"sbi.co.in", "sbicard.com",
		"axisbank.com",
		"kotak.com",
		"yesbank.in",
		"indusind.com",
		"idfcfirstbank.com",
		"aubank.in",
		"federalbank.co.in",
		"rblbank.com",
		"bankofbaroda.com", "bobfinancial.com",
		"pnb.co.in",
		"canarabank.com",
		"unionbankofindia.co.in",
		"sc.com",
		"hsbc.co.in",
		"aexp.com",
		// UPI apps and wallets
		"paytm.com", "phonepe.com", "amazonpay.in", "mobikwik.com", "cred.club",
		// Billers: telecom, broadband, electricity
		"airtel.com", "airtel.in", "jio.com", "myvi.in", "actcorp.in",
		"tatapower.com", "adanielectricity.com", "bescom.co.in",
	}
	FinancialSuffixDomains = []string{
		"bank.in", // RBI's reserved domain for Indian banks: only regulated banks can hold one
	}
	FinancialAddresses = []string{
		"googlepay-noreply@google.com",
	}
	// ReceiptDomains send order receipts (AOS-903): quick commerce, food delivery and e-commerce.
	// They also send promotions, so their mail is a candidate only when the subject reads like an
	// order (ReceiptSubjects, whole words), and like bank mail it is read only when Gmail
	// authenticated the sender (Qualify, AOS-624). Each domain has a parser in internal/receipts; a
	// test keeps the two lists equal.
	ReceiptDomains = []string{
		"swiggy.in", "swiggy.com", "zomato.com", "zeptonow.com", "zepto.com",
		"blinkit.com", "grofers.com", "amazon.in", "flipkart.com",
	}
	ReceiptSubjects = []string{"order", "delivered", "receipt", "invoice", "refund", "cancelled", "canceled", "bill"}
)

var receiptSubject = func() *regexp.Regexp {
	return regexp.MustCompile(`(?i)\b(?:` + strings.Join(ReceiptSubjects, "|") + `)\b`)
}()

// SearchQuery is the Gmail search for mail from the financial senders in the last `days` days:
// newer_than:Nd (from:(…) OR (from:(receipt senders) subject:(order words))). The search is only a pre-filter: every result is qualified again locally
// (Qualify), because a search on From also matches display names and unauthenticated mail.
func SearchQuery(days int) string {
	senders := make([]string, 0, len(FinancialDomains)+len(FinancialSuffixDomains)+len(FinancialAddresses))
	senders = append(senders, FinancialDomains...)
	senders = append(senders, FinancialSuffixDomains...)
	senders = append(senders, FinancialAddresses...)
	return fmt.Sprintf("newer_than:%dd (from:(%s) OR (from:(%s) subject:(%s)))", days,
		strings.Join(senders, " OR "), strings.Join(ReceiptDomains, " OR "), strings.Join(ReceiptSubjects, " OR "))
}

// receiptOrganisation is the listed receipt sender (AOS-903) that an address belongs to when the
// subject reads like an order, or "". Alignment (authn.go) is checked against it, as for banks.
func receiptOrganisation(addr, subject string) string {
	at := strings.LastIndexByte(addr, '@')
	if at <= 0 || at == len(addr)-1 || !receiptSubject.MatchString(subject) {
		return ""
	}
	domain := addr[at+1:]
	for _, d := range ReceiptDomains {
		if domain == d || strings.HasSuffix(domain, "."+d) {
			return d
		}
	}
	return ""
}

// senderOrganisation is the listed organisation domain that a sender address belongs to, or ""
// when the address isn't a listed financial sender. Alignment (authn.go) is checked against it.
func senderOrganisation(addr string) string {
	at := strings.LastIndexByte(addr, '@')
	if at <= 0 || at == len(addr)-1 {
		return ""
	}
	domain := addr[at+1:]
	for _, a := range FinancialAddresses {
		if addr == a {
			return domain
		}
	}
	for _, d := range FinancialDomains {
		if domain == d || strings.HasSuffix(domain, "."+d) {
			return d
		}
	}
	for _, suffix := range FinancialSuffixDomains {
		if !strings.HasSuffix(domain, "."+suffix) {
			continue
		}
		labels := strings.Split(strings.TrimSuffix(domain, "."+suffix), ".")
		if bank := labels[len(labels)-1]; bank != "" {
			return bank + "." + suffix
		}
	}
	return ""
}

// senderAddress is the lower-case address of a From header ("HDFC Bank <alerts@hdfcbank.net>"),
// or "" when the header isn't exactly one well-formed address. There is no lenient fallback: a
// header that doesn't parse can't be tied to the domain Gmail authenticated (fail closed).
func senderAddress(from string) string {
	a, err := fromParser.Parse(from)
	if err != nil {
		return ""
	}
	return strings.ToLower(a.Address)
}

// fromParser decodes display names in any charset well enough to reach the address: the name is
// never used, so an unknown charset must not make a real bank's From header unparseable.
var fromParser = &mail.AddressParser{WordDecoder: &mime.WordDecoder{
	CharsetReader: func(_ string, input io.Reader) (io.Reader, error) { return input, nil },
}}
