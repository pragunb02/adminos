package categorizer

import (
	"strings"
)

// CategoryResult holds the categorization output.
type CategoryResult struct {
	Category    string  `json:"category"`
	Subcategory string  `json:"subcategory,omitempty"`
	Confidence  float64 `json:"confidence"`
	Source      string  `json:"source"` // "rules" or "ai"
}

// rule defines a pattern-based categorization rule.
type rule struct {
	patterns    []string
	category    string
	subcategory string
	confidence  float64
}

var rules = []rule{
	{[]string{"zomato", "swiggy", "uber eats", "dominos", "mcdonalds", "kfc", "pizza hut"}, "food", "food_delivery", 0.9},
	{[]string{"uber", "ola", "rapido", "metro", "irctc"}, "transport", "", 0.9},
	{[]string{"amazon", "flipkart", "myntra", "ajio", "meesho"}, "shopping", "online", 0.85},
	{[]string{"netflix", "hotstar", "spotify", "prime video", "youtube premium", "jio cinema"}, "subscription", "entertainment", 0.9},
	{[]string{"emi", "loan", "equated", "bajaj finserv"}, "emi", "", 0.85},
	{[]string{"electricity", "water", "gas", "broadband", "airtel", "jio", "vi ", "bsnl"}, "utilities", "", 0.85},
	{[]string{"atm", "cash", "withdrawal"}, "transfer", "atm", 0.8},
	{[]string{"gym", "fitpass", "cult.fit", "healthify"}, "health", "fitness", 0.85},
	{[]string{"hospital", "pharmacy", "medplus", "apollo", "1mg"}, "health", "medical", 0.85},
	{[]string{"school", "college", "university", "udemy", "coursera"}, "education", "", 0.85},
}

// Categorize attempts to categorize a transaction by merchant name.
// Always returns a non-nil result. Returns category "other" with low confidence
// if no rule matches — caller should enqueue AI categorization fallback
// when confidence < 0.7.
func Categorize(merchantName string) *CategoryResult {
	lower := strings.ToLower(merchantName)

	for _, r := range rules {
		for _, pattern := range r.patterns {
			if strings.Contains(lower, pattern) {
				return &CategoryResult{
					Category:    r.category,
					Subcategory: r.subcategory,
					Confidence:  r.confidence,
					Source:      "rules",
				}
			}
		}
	}

	// No match — return "other" with low confidence
	// Caller should enqueue AI categorization fallback
	return &CategoryResult{
		Category:   "other",
		Confidence: 0.3,
		Source:     "rules",
	}
}
