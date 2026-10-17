package receipts

import (
	"regexp"
	"strings"
)

// One strategy per sender. Each is a layout for the shared engine plus what is particular to that
// app. App names are merchant-catalogue brands, so the API can tie a receipt to its payment.

// Swiggy covers food delivery and Instamart (quick commerce): both come from swiggy.in, and the
// receipt says which.
func Swiggy() Parser {
	return layout{
		name: "swiggy", app: "Swiggy", vertical: FoodDelivery, domains: []string{"swiggy.in", "swiggy.com"},
		orderID: genericOrderID, totals: commonTotals, refunds: commonRefunds, fees: commonFeeLabels,
		classify: func(subject string, lines []string) (string, Vertical) {
			if mentions(subject, lines, instamart) {
				return "Swiggy Instamart", QuickCommerce
			}
			return "Swiggy", FoodDelivery
		},
	}
}

var instamart = regexp.MustCompile(`(?i)\binstamart\b`)

// Zomato is food delivery. Blinkit (also Zomato's) mails from its own domain.
func Zomato() Parser {
	return layout{
		name: "zomato", app: "Zomato", vertical: FoodDelivery, domains: []string{"zomato.com"},
		orderID: genericOrderID, totals: commonTotals, refunds: commonRefunds, fees: commonFeeLabels,
	}
}

// Zepto is quick commerce.
func Zepto() Parser {
	return layout{
		name: "zepto", app: "Zepto", vertical: QuickCommerce, domains: []string{"zeptonow.com", "zepto.com"},
		orderID: genericOrderID, totals: commonTotals, refunds: commonRefunds, fees: commonFeeLabels,
	}
}

// Blinkit is quick commerce (formerly Grofers).
func Blinkit() Parser {
	return layout{
		name: "blinkit", app: "Blinkit", vertical: QuickCommerce, domains: []string{"blinkit.com", "grofers.com"},
		orderID: genericOrderID, totals: commonTotals, refunds: commonRefunds, fees: commonFeeLabels,
	}
}

// Amazon order numbers look like 402-1234567-1234567; quantities are listed per product.
func Amazon() Parser {
	return layout{
		name: "amazon", app: "Amazon", vertical: Ecommerce, domains: []string{"amazon.in"},
		orderID: regexp.MustCompile(`\b(\d{3}-\d{7}-\d{7})\b`),
		totals:  labels("order total", "grand total", "total amount", "amount paid"),
		refunds: commonRefunds, fees: commonFeeLabels,
		items: func(lines []string) int {
			if n := sumQuantities(lines); n > 0 {
				return n
			}
			return countItems(lines)
		},
	}
}

// Flipkart order IDs start with OD; Flipkart Minutes orders are quick commerce.
func Flipkart() Parser {
	return layout{
		name: "flipkart", app: "Flipkart", vertical: Ecommerce, domains: []string{"flipkart.com"},
		orderID: regexp.MustCompile(`\b(OD\d{10,24})\b`),
		totals:  labels("total amount", "order total", "amount paid", "grand total"),
		refunds: commonRefunds, fees: commonFeeLabels,
		items: func(lines []string) int {
			if n := sumQuantities(lines); n > 0 {
				return n
			}
			return countItems(lines)
		},
		classify: func(subject string, lines []string) (string, Vertical) {
			if mentions(subject, lines, flipkartMinutes) {
				return "Flipkart Minutes", QuickCommerce
			}
			return "Flipkart", Ecommerce
		},
	}
}

var flipkartMinutes = regexp.MustCompile(`(?i)\bflipkart\s+minutes\b`)

// mentions reports whether the subject or the first lines (the header of the receipt) match re.
func mentions(subject string, lines []string, re *regexp.Regexp) bool {
	if re.MatchString(subject) {
		return true
	}
	head := lines
	if len(head) > 5 {
		head = head[:5]
	}
	return re.MatchString(strings.Join(head, "\n"))
}
