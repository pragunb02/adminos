package handlers

import (
	"encoding/json"
	"log"
	"net/mail"
	"regexp"
	"strconv"
	"strings"
)

// Pay-later (BNPL) provider email (AOS-909, D-89). A provider's bill becomes a bill of type pay_later
// (the dues calendar and reminders, AOS-503); a purchase becomes a spend at the merchant paid with
// pay_later and tagged with the provider; a "payment received" email is nothing (the bank alert records
// the money). The same providers are data in the API (PayLaterProviders); the two lists name them alike.

type payLaterProvider struct {
	name    string
	domains []string
	// phrase must appear when the domain also sends other mail (Amazon, Flipkart, Paytm); nil = any mail from the domain.
	phrase *regexp.Regexp
}

var payLaterProviders = []payLaterProvider{
	{"Amazon Pay Later", []string{"amazonpay.in", "amazon.in"}, regexp.MustCompile(`(?i)\bamazon\s*pay\s*later\b`)},
	{"Flipkart Pay Later", []string{"flipkart.com"}, regexp.MustCompile(`(?i)\bflipkart\s*pay\s*later\b`)},
	{"Paytm Postpaid", []string{"paytm.com"}, regexp.MustCompile(`(?i)\bpaytm\s*postpaid\b`)},
	{"MobiKwik ZIP", []string{"mobikwik.com"}, regexp.MustCompile(`(?i)\bmobikwik\s*zip\b|\bzip\s+(?:by|from)\s+mobikwik\b`)},
	{"LazyPay", []string{"lazypay.in"}, nil},
	{"Simpl", []string{"getsimpl.com"}, nil},
}

// PayLaterDomains are the providers' own domains: added to the Gmail sender filters.
var PayLaterDomains = []string{"lazypay.in", "getsimpl.com"}

type payLaterKind int

const (
	payLaterNone payLaterKind = iota
	payLaterBill
	payLaterPurchase
	payLaterRepayment
	payLaterOther
)

var (
	payLaterRepaid   = regexp.MustCompile(`(?i)\b(?:re)?payment\b.{0,50}\b(?:received|successful|credited|processed)\b|\bthanks?(?: you)? for (?:the |your )?(?:re)?payment\b|\bbill (?:is |has been )?paid\b`)
	payLaterBillWord = regexp.MustCompile(`(?i)\b(?:bill|statement|total due|amount due|due date|due on|due by|pay by|repay by)\b`)
	payLaterBought   = regexp.MustCompile(`(?i)\b(?:spent|paid|purchase|transaction)\b`)
	payLaterMerchant = regexp.MustCompile(`(?i)\b(?:at|to|on)\s+([A-Za-z0-9][A-Za-z0-9 &.'-]{1,40}?)\s+(?:using|via|with|through)\b`)
	// payLaterMaxAmount: no pay-later bill or purchase is above ₹10 lakh; larger is a mis-read or hostile email.
	payLaterMaxAmount = 1_000_000.0
)

// payLaterProviderFor is the provider an email is from, or nil: the sender's domain decides, plus the
// provider's phrase in the subject where the domain also sends other mail (a Flipkart order "paid via
// Flipkart Pay Later" stays a receipt). A display name never counts.
func payLaterProviderFor(email GmailEmail) *payLaterProvider {
	addr, err := mail.ParseAddress(email.From)
	if err != nil {
		return nil
	}
	at := strings.LastIndexByte(addr.Address, '@')
	if at < 0 {
		return nil
	}
	domain := strings.ToLower(addr.Address[at+1:])
	for i := range payLaterProviders {
		p := &payLaterProviders[i]
		for _, d := range p.domains {
			if domain == d || strings.HasSuffix(domain, "."+d) {
				if p.phrase == nil || p.phrase.MatchString(email.Subject) {
					return p
				}
			}
		}
	}
	return nil
}

func payLaterKindOf(content string) payLaterKind {
	switch {
	case payLaterRepaid.MatchString(content):
		return payLaterRepayment
	case payLaterBought.MatchString(content) && payLaterMerchant.MatchString(content):
		return payLaterPurchase
	case payLaterBillWord.MatchString(content):
		return payLaterBill
	default:
		return payLaterOther
	}
}

// processPayLater handles a provider's email; it reports false when the email isn't from a provider.
func (h *GmailHandler) processPayLater(userID string, email GmailEmail, result *GmailResult) bool {
	p := payLaterProviderFor(email)
	if p == nil {
		return false
	}
	content := email.Subject + "\n" + email.Body
	amount, _ := extractAmount(content)
	switch payLaterKindOf(content) {
	case payLaterBill:
		if amount <= 0 || amount > payLaterMaxAmount {
			result.FailedItems++
			log.Printf("[gmail] Pay-later bill in message %s skipped: no plausible amount", email.MessageID)
			return true
		}
		result.BillsFound++
		h.processBill(userID, &ExtractedFinancialData{Type: "bill", BillerName: p.name, Amount: amount, BillType: "pay_later",
			BillDueDate: extractDueDate(content), Confidence: 0.9}, result)
	case payLaterPurchase:
		at := occurredAt(email.Date, "")
		if amount <= 0 || amount > payLaterMaxAmount || at.IsZero() || email.MessageID == "" {
			result.FailedItems++
			return true
		}
		merchant := strings.TrimSpace(payLaterMerchant.FindStringSubmatch(content)[1])
		method := "pay_later"
		meta, _ := json.Marshal(map[string]string{"payLater": p.name})
		metaText := string(meta)
		result.TransactionsFound++
		result.Events = append(result.Events, IngestEventRecord{
			UserID: userID, SourceRef: email.MessageID, OccurredAt: at, Direction: "debit",
			Amount: strconv.FormatFloat(amount, 'f', 2, 64), MerchantRaw: optional(truncate(merchant, 255), ""),
			PaymentMethod: &method, Metadata: &metaText,
		})
	default: // a repayment confirmation, an offer, a limit change
		result.PayLaterSkipped++
	}
	return true
}
