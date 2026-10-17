package gmail

import (
	"fmt"
	"net/mail"
	"regexp"
	"strings"
)

// Financial senders and subjects (AOS-401, D-59): only mail that matches these is ever fetched in
// full, so personal mail is never read. This is data: adding a bank or biller is one line here.
//
//   - a Domain matches the sender's domain or any subdomain ("hdfcbank.net" matches
//     alerts@hdfcbank.net and x@mail.hdfcbank.net);
//   - an Address matches exactly;
//   - a Subject phrase matches anywhere in the subject, case-insensitively.
var (
	FinancialDomains = []string{
		// Banks and card issuers
		"bank.in", // RBI's reserved domain for Indian banks: only regulated banks can hold one
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
		// Pay-later providers (AOS-909)
		"lazypay.in", "getsimpl.com",
		// Billers: telecom, broadband, electricity
		"airtel.com", "airtel.in", "jio.com", "myvi.in", "actcorp.in",
		"tatapower.com", "adanielectricity.com", "bescom.co.in",
	}
	FinancialAddresses = []string{
		"googlepay-noreply@google.com",
	}
	FinancialSubjects = []string{
		"transaction alert",
		"has been debited",
		"has been credited",
		"payment received",
		"payment due",
		"bill generated",
		"credit card statement",
		"autopay",
		"e-mandate",
	}
	// ReceiptDomains send order receipts (AOS-903): quick commerce, food delivery and e-commerce.
	// They also send promotions, so their mail is read only when the subject reads like an order
	// (ReceiptSubjects, whole words). Each domain has a parser in internal/receipts; a test keeps
	// the two lists equal.
	ReceiptDomains = []string{
		"swiggy.in", "swiggy.com", "zomato.com", "zeptonow.com", "zepto.com",
		"blinkit.com", "grofers.com", "amazon.in", "flipkart.com",
	}
	ReceiptSubjects = []string{"order", "delivered", "receipt", "invoice", "refund", "cancelled", "canceled", "bill"}
	// BookingDomains send travel bookings (AOS-910): airlines, IRCTC, redBus. Read only for booking-like
	// subjects (BookingSubjects). Each has a parser in internal/bookings; a test keeps the lists equal.
	BookingDomains  = []string{"goindigo.in", "airindia.com", "airindia.in", "akasaair.com", "irctc.co.in", "redbus.in"}
	BookingSubjects = []string{"booking", "booked", "ticket", "itinerary", "pnr", "confirmed", "confirmation", "cancelled", "cancellation"}
)

var bookingSubject = func() *regexp.Regexp {
	return regexp.MustCompile(`(?i)\b(?:` + strings.Join(BookingSubjects, "|") + `)\b`)
}()

var receiptSubject = func() *regexp.Regexp {
	return regexp.MustCompile(`(?i)\b(?:` + strings.Join(ReceiptSubjects, "|") + `)\b`)
}()

// SearchQuery is the Gmail search for financial mail in the last `days` days:
// newer_than:Nd (from:(…) OR subject:(…) OR (from:(receipt senders) subject:(order words))).
func SearchQuery(days int) string {
	senders := make([]string, 0, len(FinancialDomains)+len(FinancialAddresses))
	senders = append(senders, FinancialDomains...)
	senders = append(senders, FinancialAddresses...)
	subjects := make([]string, len(FinancialSubjects))
	for i, s := range FinancialSubjects {
		subjects[i] = `"` + s + `"`
	}
	return fmt.Sprintf("newer_than:%dd (from:(%s) OR subject:(%s) OR (from:(%s) subject:(%s)) OR (from:(%s) subject:(%s)))", days,
		strings.Join(senders, " OR "), strings.Join(subjects, " OR "),
		strings.Join(ReceiptDomains, " OR "), strings.Join(ReceiptSubjects, " OR "),
		strings.Join(BookingDomains, " OR "), strings.Join(BookingSubjects, " OR "))
}

// IsFinancial applies the same filters locally to a message's From and Subject headers. Used for
// incremental sync, where history.list returns every new message and only their headers are read.
func IsFinancial(from, subject string) bool {
	if addr := senderAddress(from); addr != "" {
		for _, a := range FinancialAddresses {
			if addr == a {
				return true
			}
		}
		if at := strings.LastIndexByte(addr, '@'); at >= 0 {
			domain := addr[at+1:]
			if inDomains(domain, FinancialDomains) {
				return true
			}
			if inDomains(domain, ReceiptDomains) && receiptSubject.MatchString(subject) {
				return true
			}
			if inDomains(domain, BookingDomains) && bookingSubject.MatchString(subject) {
				return true
			}
		}
	}
	subject = strings.ToLower(subject)
	for _, s := range FinancialSubjects {
		if strings.Contains(subject, s) {
			return true
		}
	}
	return false
}

// inDomains reports whether domain is one of domains or a subdomain of one.
func inDomains(domain string, domains []string) bool {
	for _, d := range domains {
		if domain == d || strings.HasSuffix(domain, "."+d) {
			return true
		}
	}
	return false
}

// senderAddress is the lower-case address in a From header ("HDFC Bank <alerts@hdfcbank.net>").
func senderAddress(from string) string {
	if a, err := mail.ParseAddress(from); err == nil {
		return strings.ToLower(a.Address)
	}
	if l, r := strings.LastIndexByte(from, '<'), strings.LastIndexByte(from, '>'); l >= 0 && r > l {
		return strings.ToLower(strings.TrimSpace(from[l+1 : r]))
	}
	return strings.ToLower(strings.TrimSpace(from))
}
