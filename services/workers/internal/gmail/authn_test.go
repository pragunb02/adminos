package gmail

import (
	"strings"
	"testing"
)

const hdfcFrom = "HDFC Bank InstaAlerts <alerts@hdfcbank.net>"

// gmailAR is a realistic Authentication-Results header as Gmail writes it (synthetic values).
func gmailAR(clauses ...string) MessageHeader {
	return MessageHeader{Name: "Authentication-Results", Value: "mx.google.com;\r\n       " + strings.Join(clauses, ";\r\n       ")}
}

func from(v string) MessageHeader { return MessageHeader{Name: "From", Value: v} }

const (
	dmarcPassHDFC = "dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=hdfcbank.net"
	dkimPassHDFC  = "dkim=pass header.i=@hdfcbank.net header.s=s1 header.b=AbCdEf12"
	spfPassHDFC   = "spf=pass (google.com: domain of alerts@hdfcbank.net designates 192.0.2.1 as permitted sender) smtp.mailfrom=alerts@hdfcbank.net"
)

func TestQualifyAdmitsAuthenticatedBankMail(t *testing.T) {
	cases := map[string][]MessageHeader{
		"dmarc pass": {gmailAR(dkimPassHDFC, spfPassHDFC, dmarcPassHDFC), from(hdfcFrom)},
		"aligned dkim pass, no dmarc": {gmailAR("dkim=pass header.i=@hdfcbank.net header.s=s1", "spf=neutral smtp.mailfrom=x@relay.example"),
			from(hdfcFrom)},
		"dkim signed by a subdomain of the organisation": {gmailAR("dkim=pass header.d=mail.hdfcbank.net"), from(hdfcFrom)},
		"From is a subdomain, dkim the organisation":     {gmailAR("dkim=pass header.i=@sbicard.com"), from("SBI Card <statements@mail.sbicard.com>")},
		".bank.in bank":                          {gmailAR("dmarc=pass header.from=kotak.bank.in"), from("Kotak <noreply@kotak.bank.in>")},
		"listed address":                         {gmailAR("dkim=pass header.i=@google.com"), from("Google Pay <googlepay-noreply@google.com>")},
		"upper-case values and versioned method": {gmailAR("DKIM/1=PASS header.d=HDFCBANK.NET"), from(hdfcFrom)},
		"Gmail's header on top, a forged one below": {gmailAR(dmarcPassHDFC), from(hdfcFrom),
			{Name: "Authentication-Results", Value: "mx.google.com; dmarc=fail header.from=hdfcbank.net"}},
	}
	for name, headers := range cases {
		if got := Qualify(headers); got != BankMail {
			t.Errorf("%s: got %v, want BankMail", name, got)
		}
	}
}

func TestQualifyRejectsSpoofedBankSenders(t *testing.T) {
	cases := map[string][]MessageHeader{
		"no Authentication-Results at all": {from(hdfcFrom)},
		"dmarc fail":                       {gmailAR("dkim=none", "spf=softfail smtp.mailfrom=x@evil.example", "dmarc=fail (p=REJECT) header.from=hdfcbank.net"), from(hdfcFrom)},
		"spf pass alone":                   {gmailAR(spfPassHDFC, "dmarc=none header.from=hdfcbank.net"), from(hdfcFrom)},
		"dmarc pass for another domain":    {gmailAR("dmarc=pass header.from=evil.example"), from(hdfcFrom)},
		"dkim pass for another domain":     {gmailAR("dkim=pass header.i=@evil.example"), from(hdfcFrom)},
		"dkim pass for a look-alike":       {gmailAR("dkim=pass header.d=hdfcbank.net.evil.example"), from(hdfcFrom)},
		"dkim pass for a sibling .bank.in": {gmailAR("dkim=pass header.d=evil.bank.in"), from("Kotak <noreply@kotak.bank.in>")},
		"dkim fail with a pass in its comment": {gmailAR("dkim=fail (dkim=pass header.d=hdfcbank.net) header.d=hdfcbank.net"),
			from(hdfcFrom)},
		"a clause smuggled into the SPF comment": {gmailAR("spf=pass (google.com: domain of x;dmarc=pass header.from=hdfcbank.net@evil.example) smtp.mailfrom=x@evil.example",
			"dmarc=fail header.from=hdfcbank.net"), from(hdfcFrom)},
		"an unbalanced comment swallows the rest": {gmailAR("spf=pass (oops", "dmarc=pass header.from=hdfcbank.net"), from(hdfcFrom)},
		"a property given twice":                  {gmailAR("dmarc=pass header.from=evil.example header.from=hdfcbank.net"), from(hdfcFrom)},
		"the topmost header isn't Gmail's": {{Name: "Authentication-Results", Value: "relay.evil.example; dmarc=pass header.from=hdfcbank.net"},
			from(hdfcFrom)},
		"Gmail's says fail, a forged pass sits below": {gmailAR("dmarc=fail header.from=hdfcbank.net"), from(hdfcFrom),
			{Name: "Authentication-Results", Value: "mx.google.com; dmarc=pass header.from=hdfcbank.net"}},
		"look-alike authserv-id": {{Name: "Authentication-Results", Value: "mx.google.com.evil.example; dmarc=pass header.from=hdfcbank.net"},
			from(hdfcFrom)},
		"empty header":            {{Name: "Authentication-Results", Value: ""}, from(hdfcFrom)},
		"result without a method": {gmailAR("=pass header.from=hdfcbank.net"), from(hdfcFrom)},
	}
	for name, headers := range cases {
		if got := Qualify(headers); got != Unauthenticated {
			t.Errorf("%s: got %v, want Unauthenticated", name, got)
		}
	}
}

func TestQualifyNeverAdmitsByDisplayNameSubjectOrMalformedFrom(t *testing.T) {
	cases := map[string][]MessageHeader{
		"a stranger with a bank-alert subject": {gmailAR("dmarc=pass header.from=gmail.com"), from("Ravi <ravi@gmail.com>"),
			{Name: "Subject", Value: "Transaction alert: Rs 500 has been debited"}},
		"bank name only in the display name": {gmailAR("dmarc=pass header.from=evil.example"), from(`"alerts@hdfcbank.net" <x@evil.example>`)},
		"two From headers":                   {gmailAR(dmarcPassHDFC), from(hdfcFrom), from("x@evil.example")},
		"no From header":                     {gmailAR(dmarcPassHDFC)},
		"two addresses in one From":          {gmailAR(dmarcPassHDFC), from("alerts@hdfcbank.net, x@evil.example")},
		"a From that doesn't parse":          {gmailAR(dmarcPassHDFC), from("HDFC <alerts@hdfcbank.net")},
		"no headers":                         nil,
	}
	for name, headers := range cases {
		if got := Qualify(headers); got != NotBankMail {
			t.Errorf("%s: got %v, want NotBankMail", name, got)
		}
	}
}

func TestStripCommentsHandlesNestingEscapesAndQuotes(t *testing.T) {
	for in, want := range map[string]string{
		`a (b (c) d) e`:      `a   e`,
		`a (b \) c) d`:       `a   d`,
		`a "q (not) q" b`:    `a "q (not) q" b`,
		`a (unclosed b; c=d`: `a  `,
	} {
		if got := stripComments(in); got != want {
			t.Errorf("stripComments(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSplitClausesIgnoresSemicolonsInQuotes(t *testing.T) {
	got := splitClauses(`mx.google.com; x=pass p.a="1;2"; y=fail`)
	if len(got) != 3 || got[1] != ` x=pass p.a="1;2"` {
		t.Errorf("got %q", got)
	}
}

// AOS-903 with AOS-624: an order receipt is read only when Gmail authenticated the receipt sender, and
// a receipt sender's promotion is not a candidate at all, authenticated or not.
func TestQualifyReceiptSenders(t *testing.T) {
	swiggy := from("Swiggy <noreply@swiggy.in>")
	order := MessageHeader{Name: "Subject", Value: "Your Swiggy order summary"}
	promo := MessageHeader{Name: "Subject", Value: "Craving biryani? 50% off tonight"}
	cases := []struct {
		name    string
		headers []MessageHeader
		want    Verdict
	}{
		{"authenticated order", []MessageHeader{gmailAR("dmarc=pass header.from=swiggy.in"), swiggy, order}, BankMail},
		{"aligned dkim order", []MessageHeader{gmailAR("dkim=pass header.d=mailer.swiggy.in"), swiggy, order}, BankMail},
		{"spoofed order", []MessageHeader{gmailAR("dmarc=fail header.from=swiggy.in", "spf=pass smtp.mailfrom=x@evil.example"), swiggy, order}, Unauthenticated},
		{"order signed by another domain", []MessageHeader{gmailAR("dkim=pass header.d=evil.example"), swiggy, order}, Unauthenticated},
		{"authenticated promotion", []MessageHeader{gmailAR("dmarc=pass header.from=swiggy.in"), swiggy, promo}, NotBankMail},
		{"no subject", []MessageHeader{gmailAR("dmarc=pass header.from=swiggy.in"), swiggy}, NotBankMail},
		{"order words from an unlisted sender", []MessageHeader{gmailAR("dmarc=pass header.from=evil.example"), from("x@evil.example"), order}, NotBankMail},
	}
	for _, c := range cases {
		if got := Qualify(c.headers); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}

// AOS-910 with AOS-624: a travel booking is read only when Gmail authenticated the booking sender, and
// a booking sender's promotion is not a candidate at all.
func TestQualifyBookingSenders(t *testing.T) {
	indigo := from("IndiGo <noreply@goindigo.in>")
	booking := MessageHeader{Name: "Subject", Value: "Your IndiGo booking is confirmed. PNR AB12CD"}
	promo := MessageHeader{Name: "Subject", Value: "Fly more this festive season"}
	cases := []struct {
		name    string
		headers []MessageHeader
		want    Verdict
	}{
		{"authenticated booking", []MessageHeader{gmailAR("dmarc=pass header.from=goindigo.in"), indigo, booking}, BankMail},
		{"spoofed booking", []MessageHeader{gmailAR("dmarc=fail header.from=goindigo.in", "spf=pass smtp.mailfrom=x@evil.example"), indigo, booking}, Unauthenticated},
		{"booking signed by another domain", []MessageHeader{gmailAR("dkim=pass header.d=evil.example"), indigo, booking}, Unauthenticated},
		{"authenticated promotion", []MessageHeader{gmailAR("dmarc=pass header.from=goindigo.in"), indigo, promo}, NotBankMail},
		{"look-alike domain", []MessageHeader{gmailAR("dmarc=pass header.from=goindigo.in.evil.example"), from("x@goindigo.in.evil.example"), booking}, NotBankMail},
	}
	for _, c := range cases {
		if got := Qualify(c.headers); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}
