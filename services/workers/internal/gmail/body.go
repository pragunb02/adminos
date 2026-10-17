package gmail

import (
	"encoding/base64"
	"html"
	"regexp"
	"strings"
)

// maxBodyBytes bounds how much of a body is kept for parsing: bank alerts are a few KB;
// a huge newsletter is cut rather than held in memory.
const maxBodyBytes = 64 << 10

// PlainBody returns the message's readable text for the parser: the text/plain part if there is
// one, otherwise the text/html part with tags removed, otherwise "". Attachments are ignored.
//
// The text stays in memory for extraction only (D-33): callers must never store or log it.
func PlainBody(msg *Message) string {
	if msg == nil {
		return ""
	}
	if text := findPart(&msg.Payload, "text/plain"); text != "" {
		return truncateBytes(normalizeSpace(text))
	}
	if markup := findPart(&msg.Payload, "text/html"); markup != "" {
		return truncateBytes(StripHTML(markup))
	}
	return ""
}

// findPart walks the MIME tree depth-first and returns the first decodable inline part of mimeType.
func findPart(p *MessagePart, mimeType string) string {
	if p.Filename == "" && p.Body.AttachmentID == "" && strings.EqualFold(baseMime(p.MimeType), mimeType) && p.Body.Data != "" {
		if text, ok := decodeBase64URL(p.Body.Data); ok {
			return text
		}
	}
	for i := range p.Parts {
		if text := findPart(&p.Parts[i], mimeType); text != "" {
			return text
		}
	}
	return ""
}

func baseMime(m string) string {
	if i := strings.IndexByte(m, ';'); i >= 0 {
		m = m[:i]
	}
	return strings.TrimSpace(m)
}

// decodeBase64URL decodes Gmail's body data: URL-safe base64, with or without padding.
func decodeBase64URL(data string) (string, bool) {
	trimmed := strings.TrimRight(data, "=")
	b, err := base64.RawURLEncoding.DecodeString(trimmed)
	if err != nil {
		// Some producers use the standard alphabet.
		if b, err = base64.RawStdEncoding.DecodeString(trimmed); err != nil {
			return "", false
		}
	}
	return string(b), true
}

var (
	// One pattern per element (RE2 has no back-references to pair open and close tags).
	invisibleBlocks = []*regexp.Regexp{
		regexp.MustCompile(`(?is)<script\b[^>]*>.*?</script\s*>`),
		regexp.MustCompile(`(?is)<style\b[^>]*>.*?</style\s*>`),
		regexp.MustCompile(`(?is)<title\b[^>]*>.*?</title\s*>`),
	}
	htmlComments = regexp.MustCompile(`(?s)<!--.*?-->`)
	blockBreaks  = regexp.MustCompile(`(?i)<\s*(br|/p|/div|/tr|/li|/h[1-6]|/table)\b[^>]*>`)
	anyTag       = regexp.MustCompile(`(?s)<[^>]*>`)
	spaces       = regexp.MustCompile(`[ \t\r\f\v\x{00a0}]+`)
	blankLines   = regexp.MustCompile(`\n\s*\n+`)
)

// StripHTML turns an HTML email into plain text: scripts, styles and comments are dropped, block
// ends become line breaks or spaces, tags are removed and entities (&amp;, &#8377; …) decoded.
func StripHTML(markup string) string {
	s := htmlComments.ReplaceAllString(markup, " ")
	for _, re := range invisibleBlocks {
		s = re.ReplaceAllString(s, " ")
	}
	s = blockBreaks.ReplaceAllString(s, "\n")
	s = anyTag.ReplaceAllString(s, " ")
	s = html.UnescapeString(s)
	return normalizeSpace(s)
}

func normalizeSpace(s string) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = spaces.ReplaceAllString(s, " ")
	lines := strings.Split(s, "\n")
	for i, l := range lines {
		lines[i] = strings.TrimSpace(l)
	}
	s = strings.Join(lines, "\n")
	s = blankLines.ReplaceAllString(s, "\n")
	return strings.TrimSpace(s)
}

func truncateBytes(s string) string {
	if len(s) <= maxBodyBytes {
		return s
	}
	cut := maxBodyBytes
	for cut > 0 && !isRuneStart(s[cut]) {
		cut--
	}
	return s[:cut]
}

func isRuneStart(b byte) bool { return b&0xC0 != 0x80 }
