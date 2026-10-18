package gmail

import (
	"strings"
)

// Verdict is whether a message may be read as bank mail (AOS-624, AUD-11).
type Verdict int

const (
	// NotBankMail: the sender isn't a listed financial sender (or the From header is malformed).
	NotBankMail Verdict = iota
	// Unauthenticated: the From names a listed sender, but Gmail didn't authenticate that domain.
	// This is what a spoofed bank alert looks like.
	Unauthenticated
	// BankMail: a listed sender, and Gmail's own Authentication-Results show a DMARC pass for the
	// From domain or a DKIM pass for a domain aligned with it.
	BankMail
)

// GmailAuthServID is the authserv-id Gmail writes into the Authentication-Results it adds.
const GmailAuthServID = "mx.google.com"

// AuthResultsHeader is the header Gmail records its SPF/DKIM/DMARC checks in (RFC 8601).
const AuthResultsHeader = "Authentication-Results"

// Qualify decides from a message's headers (in message order, as Gmail returns them) whether it is
// authenticated mail from a listed financial sender. Every doubt is a rejection:
//
//   - exactly one From header holding exactly one address, whose domain is a listed sender (or a
//     receipt sender, AOS-903, when the subject reads like an order, or a travel-booking sender,
//     AOS-910, when it reads like a booking);
//   - the topmost Authentication-Results header must be Gmail's (authserv-id mx.google.com). Gmail
//     prepends its own, so a header the sender wrote into the message is always below it and is
//     never read;
//   - that header must show dmarc=pass with header.from equal to the From domain, or dkim=pass
//     with a signing domain (header.d, else header.i) inside the sender's listed organisation.
//
// SPF alone never qualifies: it authenticates the envelope sender, not the From the user sees.
func Qualify(headers []MessageHeader) Verdict {
	var froms []string
	subject := ""
	authResults := ""
	seenAuthResults := false
	for _, h := range headers {
		switch {
		case strings.EqualFold(h.Name, "From"):
			froms = append(froms, h.Value)
		case strings.EqualFold(h.Name, "Subject") && subject == "":
			subject = h.Value
		case strings.EqualFold(h.Name, AuthResultsHeader) && !seenAuthResults:
			authResults, seenAuthResults = h.Value, true
		}
	}
	if len(froms) != 1 {
		return NotBankMail
	}
	addr := senderAddress(froms[0])
	org := senderOrganisation(addr)
	if org == "" {
		org = receiptOrganisation(addr, subject)
	}
	if org == "" {
		org = bookingOrganisation(addr, subject)
	}
	if org == "" {
		return NotBankMail
	}
	fromDomain := addr[strings.LastIndexByte(addr, '@')+1:]
	if authenticated(authResults, fromDomain, org) {
		return BankMail
	}
	return Unauthenticated
}

// authenticated reports whether Gmail's Authentication-Results authenticate fromDomain.
func authenticated(header, fromDomain, org string) bool {
	authServID, results, ok := parseAuthResults(header)
	if !ok || authServID != GmailAuthServID {
		return false
	}
	for _, r := range results {
		if r.result != "pass" {
			continue
		}
		switch r.method {
		case "dmarc":
			if r.props["header.from"] == fromDomain {
				return true
			}
		case "dkim":
			if d := signingDomain(r.props); d != "" && inOrganisation(d, org) {
				return true
			}
		}
	}
	return false
}

// signingDomain is a DKIM result's signing domain: header.d, else the domain of header.i.
func signingDomain(props map[string]string) string {
	if d := props["header.d"]; d != "" {
		return d
	}
	i := props["header.i"]
	if at := strings.LastIndexByte(i, '@'); at >= 0 {
		return i[at+1:]
	}
	return i
}

// inOrganisation: domain is org or a subdomain of it (relaxed DMARC alignment against the listed
// organisation, so a sibling under a registry suffix such as other.bank.in never aligns).
func inOrganisation(domain, org string) bool {
	return domain == org || strings.HasSuffix(domain, "."+org)
}

// authResult is one "method=result prop=value …" clause of an Authentication-Results header.
type authResult struct {
	method string
	result string
	props  map[string]string
}

// parseAuthResults parses an RFC 8601 Authentication-Results value into its authserv-id and its
// results. Comments are removed first (with nesting and escapes), so text an attacker controls
// inside a comment, such as the envelope sender Gmail quotes in the SPF comment, can't add a
// clause. Names, results and domains are lower-cased.
func parseAuthResults(header string) (string, []authResult, bool) {
	clauses := splitClauses(stripComments(header))
	if len(clauses) == 0 {
		return "", nil, false
	}
	idFields := strings.Fields(clauses[0])
	if len(idFields) == 0 {
		return "", nil, false
	}
	authServID := strings.ToLower(idFields[0])
	var results []authResult
	for _, clause := range clauses[1:] {
		fields := strings.Fields(clause)
		if len(fields) == 0 {
			continue
		}
		method, result, ok := strings.Cut(strings.ToLower(fields[0]), "=")
		if !ok || method == "" || result == "" {
			continue
		}
		method, _, _ = strings.Cut(method, "/") // "dkim/1=pass": the version is irrelevant here
		r := authResult{method: method, result: result, props: map[string]string{}}
		for _, f := range fields[1:] {
			name, value, ok := strings.Cut(f, "=")
			if !ok || !strings.Contains(name, ".") {
				continue // "reason=…" and stray words
			}
			name = strings.ToLower(name)
			if _, dup := r.props[name]; dup {
				r.props[name] = "" // a property given twice is ambiguous: trust neither
				continue
			}
			r.props[name] = strings.ToLower(strings.Trim(value, `"`))
		}
		results = append(results, r)
	}
	return authServID, results, true
}

// stripComments removes RFC 5322 comments ("(…)", nested, with backslash escapes) outside quoted
// strings. An unbalanced comment swallows the rest of the header (fail closed).
func stripComments(s string) string {
	var b strings.Builder
	depth, inQuote, escaped := 0, false, false
	for _, c := range s {
		switch {
		case escaped:
			escaped = false
			if depth == 0 {
				b.WriteRune(c)
			}
		case c == '\\':
			escaped = true
			if depth == 0 {
				b.WriteRune(c)
			}
		case depth == 0 && c == '"':
			inQuote = !inQuote
			b.WriteRune(c)
		case !inQuote && c == '(':
			if depth == 0 {
				b.WriteRune(' ') // a comment separates tokens like whitespace
			}
			depth++
		case !inQuote && depth > 0 && c == ')':
			depth--
		case depth == 0:
			b.WriteRune(c)
		}
	}
	return b.String()
}

// splitClauses splits on ';' outside quoted strings.
func splitClauses(s string) []string {
	var out []string
	inQuote, escaped, start := false, false, 0
	for i, c := range s {
		switch {
		case escaped:
			escaped = false
		case c == '\\':
			escaped = true
		case c == '"':
			inQuote = !inQuote
		case c == ';' && !inQuote:
			out = append(out, s[start:i])
			start = i + 1
		}
	}
	return append(out, s[start:])
}
