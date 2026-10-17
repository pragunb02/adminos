package receipts

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/adminos/adminos/workers/internal/gmail"
)

// Security (D-100): receipt emails are attacker-controllable. Anyone can send mail that claims to be
// from an app. These tests feed hostile emails and check that the parsers fail closed: a refused or
// skipped email, never a panic, a wrong number, or untrusted text in what is stored.

var orderDate = time.Date(2026, 10, 17, 16, 10, 0, 0, time.UTC)

func swiggyEmail(subject, body string) Email {
	return Email{MessageID: "m", From: "Swiggy <noreply@swiggy.in>", Subject: subject, Body: body, Date: orderDate}
}

func TestSpoofedAndLookAlikeSendersHaveNoParser(t *testing.T) {
	reg := Default()
	for _, from := range []string{
		`"noreply@swiggy.in" <attacker@evil.example>`, // the display name claims the app
		"Swiggy <noreply@swiggy.in.evil.example>",     // the app's domain as a subdomain of another
		"Swiggy <noreply@swіggy.in>",                  // Cyrillic і (U+0456), a homoglyph
		"Swiggy <noreply@xn--swggy-4ye.in>",           // the same homoglyph as punycode
		"noreply@swiggy.in@evil.example",              // two @: the last one is the domain
		"Swiggy <noreply@swiggy.in\u200b>",            // a hidden character in the domain
		"",
	} {
		if p := reg.ParserFor(from); p != nil {
			t.Errorf("ParserFor(%q) = %s; a look-alike must not be read as the app", from, p.Name())
		}
	}
	// A display name with a real address is still that address's sender.
	if p := reg.ParserFor(`"Zomato" <noreply@swiggy.in>`); p == nil || p.Name() != "swiggy" {
		t.Error("the address, not the display name, decides")
	}
}

func TestHiddenCharactersInsideAmountsAndLabelsAreRemovedBeforeReading(t *testing.T) {
	body := "Order ID: 187654321098\n\u202eGrand Total ₹4\u200b65.00\nDelivery\u200c Fee ₹3\ufeff5.00\nPaid via UPI"
	r, err := Swiggy().Parse(swiggyEmail("Your Swiggy order\u200b summary", body))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if r.Total != 46500 || len(r.Fees) != 1 || r.Fees[0] != (Fee{FeeDelivery, 3500}) {
		t.Errorf("hidden characters changed the reading: total=%d fees=%+v", r.Total, r.Fees)
	}
}

func TestLookAlikeDigitsNeverReadAsAnAmount(t *testing.T) {
	for _, total := range []string{"₹４６５.００", "₹४६५", "₹٤٦٥"} { // full-width, Devanagari, Arabic-Indic
		_, err := Swiggy().Parse(swiggyEmail("Your Swiggy order summary", "Order ID: 187654321098\nGrand Total "+total))
		if !errors.Is(err, ErrNotAReceipt) {
			t.Errorf("%s: want the receipt skipped (no ASCII amount), got %v", total, err)
		}
	}
}

func TestImplausibleNumbersAreRefused(t *testing.T) {
	cases := []string{
		"Order ID: 187654321098\nGrand Total ₹99,99,99,999.00",                // over ₹50 lakh
		"Order ID: 187654321098\nGrand Total ₹100.00\nItem count: 999",        // over 500 items
		"Order ID: 187654321098\nGrand Total ₹100.00\nTip ₹60.00\nTip ₹60.00", // fees over the total
		"Order ID: 187654321098\nGrand Total ₹100.00\nPaid via UPI ₹150.00",   // paid over the total
		"Order ID: 187654321098\nGrand Total ₹99999999999999999999999.00",     // overflows: no amount
	}
	for i, body := range cases {
		_, err := Swiggy().Parse(swiggyEmail("Your Swiggy order summary", body))
		if err == nil {
			t.Errorf("case %d: a hostile receipt was accepted", i)
		}
	}
}

func TestAnOversizedBodyIsBoundedAndFastWithoutPanicking(t *testing.T) {
	line := strings.Repeat("₹9", 200_000) // one 600 KB line of amounts
	many := "Order ID: 187654321098\nGrand Total ₹100.00\n" + strings.Repeat("Tip ₹0.01\n", 50_000)
	start := time.Now()
	for _, body := range []string{line, many, strings.Repeat("\n", 1_000_000), strings.Repeat("Order ID: ", 100_000)} {
		_, _ = Swiggy().Parse(swiggyEmail("Your Swiggy order summary", body))
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Errorf("hostile bodies took %v (patterns must stay linear)", elapsed)
	}
	r, err := Swiggy().Parse(swiggyEmail("Your Swiggy order summary", many))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if got := r.FeeTotal(); got > Paise(maxLines) {
		t.Errorf("only the first %d lines are read, got fees %d", maxLines, got)
	}
}

func TestScriptAndMarkupNeverReachTheReceipt(t *testing.T) {
	html := `<html><head><script>document.write("Grand Total ₹1.00")</script><style>.x{}</style></head><body>
<table><tr><td>Order ID: 187654321098</td></tr>
<tr><td><img src=x onerror="alert(1)">Paneer &lt;script&gt;alert(1)&lt;/script&gt;</td><td>₹220.00</td></tr>
<tr><td>Delivery Fee</td><td>₹35.00</td></tr>
<tr><td>Grand Total</td><td>₹255.00</td></tr>
<tr><td>Paid via <b>UPI</b></td></tr><!-- Grand Total ₹9999 --></table></body></html>`
	r, err := Swiggy().Parse(swiggyEmail("Your Swiggy order summary", gmail.StripHTML(html)))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if r.Total != 25500 || r.Payment != PayUPI || len(r.Fees) != 1 {
		t.Errorf("script, comment or markup changed the numbers: %+v", r)
	}
	// What is stored is the parser's own app name, enums, numbers and a hash: no text from the email.
	for _, field := range []string{r.App, string(r.Vertical), string(r.Kind), string(r.Payment), r.OrderRefHash()} {
		if strings.ContainsAny(field, "<>\"'&") {
			t.Errorf("stored field carries markup: %q", field)
		}
	}
}

func TestMalformedHTMLFailsClosed(t *testing.T) {
	for _, html := range []string{
		`<td>Order ID: 187654321098<td>Grand Total ₹<b>465</td`,                // unclosed tags
		`<<<>>>Order ID: 187654321098</p></p></div>Grand Total`,                // no amount after the label
		"<td>Order ID: 187654321098</td>\x00\x01<td>Grand Total ₹\xff465</td>", // control bytes, invalid UTF-8
		`<!-- <td>Grand Total ₹465</td> --> Order ID: 187654321098`,            // the total only in a comment
	} {
		r, err := Swiggy().Parse(swiggyEmail("Your Swiggy order summary", gmail.StripHTML(html)))
		if err == nil && r.Total != 46500 {
			t.Errorf("malformed HTML read a wrong total %d from %q", r.Total, html)
		}
	}
}

func TestAnOrderIDCannotSmuggleText(t *testing.T) {
	r, err := Swiggy().Parse(swiggyEmail("Your Swiggy order summary",
		"Order ID: ABC123<script>alert(1)</script>\nGrand Total ₹100.00"))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if r.OrderID != "ABC123" {
		t.Errorf("the order number is letters, digits and dashes only, got %q", r.OrderID)
	}
}
