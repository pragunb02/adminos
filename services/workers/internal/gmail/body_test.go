package gmail

import (
	"encoding/base64"
	"strings"
	"testing"
)

func b64url(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }

func TestPlainBodyPrefersTextPlain(t *testing.T) {
	msg := &Message{Payload: MessagePart{MimeType: "multipart/alternative", Parts: []MessagePart{
		{MimeType: "text/html", Body: MessagePartBody{Data: b64url("<p>Rs 349.00 debited</p>")}},
		{MimeType: "text/plain; charset=UTF-8", Body: MessagePartBody{Data: b64url("Rs 349.00 debited from your A/c XX1234 at ZOMATO")}},
	}}}

	if got := PlainBody(msg); got != "Rs 349.00 debited from your A/c XX1234 at ZOMATO" {
		t.Errorf("got %q", got)
	}
}

func TestPlainBodyStripsHTMLWhenThereIsNoTextPart(t *testing.T) {
	html := `<html><head><style>p{color:red}</style><title>Alert</title></head><body>
		<script>track()</script><!-- hidden -->
		<table><tr><td>Amount</td><td>&#8377;&nbsp;1,250.00</td></tr></table>
		<p>Dear Customer,<br>Rs 1,250.00 has been debited from your A/c XX1234 to VPA swiggy@icici &amp; co.</p></body></html>`
	msg := &Message{Payload: MessagePart{MimeType: "text/html", Body: MessagePartBody{Data: b64url(html)}}}

	got := PlainBody(msg)

	for _, want := range []string{"Amount ₹ 1,250.00", "Rs 1,250.00 has been debited from your A/c XX1234 to VPA swiggy@icici & co."} {
		if !strings.Contains(got, want) {
			t.Errorf("want %q in %q", want, got)
		}
	}
	for _, unwanted := range []string{"track()", "color:red", "hidden", "<", "Alert"} {
		if strings.Contains(got, unwanted) {
			t.Errorf("%q must be stripped from %q", unwanted, got)
		}
	}
}

func TestPlainBodyReadsNestedPartsAndIgnoresAttachments(t *testing.T) {
	msg := &Message{Payload: MessagePart{MimeType: "multipart/mixed", Parts: []MessagePart{
		{MimeType: "text/plain", Filename: "statement.txt", Body: MessagePartBody{AttachmentID: "att1"}},
		{MimeType: "text/plain", Filename: "notes.txt", Body: MessagePartBody{Data: b64url("attached file")}},
		{MimeType: "multipart/alternative", Parts: []MessagePart{
			{MimeType: "text/plain", Body: MessagePartBody{Data: b64url("Your HDFC Bank Credit Card bill of Rs 18,450 is due on 05/11/2026")}},
		}},
	}}}

	if got := PlainBody(msg); got != "Your HDFC Bank Credit Card bill of Rs 18,450 is due on 05/11/2026" {
		t.Errorf("got %q", got)
	}
}

func TestPlainBodyAcceptsPaddedAndStandardBase64(t *testing.T) {
	padded := base64.URLEncoding.EncodeToString([]byte("Rs 5 credited"))
	std := base64.StdEncoding.EncodeToString([]byte("Rs 6 credited??>"))
	for data, want := range map[string]string{padded: "Rs 5 credited", std: "Rs 6 credited??>"} {
		msg := &Message{Payload: MessagePart{MimeType: "text/plain", Body: MessagePartBody{Data: data}}}
		if got := PlainBody(msg); got != want {
			t.Errorf("%q: got %q, want %q", data, got, want)
		}
	}
}

func TestPlainBodyIsEmptyWithoutReadableText(t *testing.T) {
	for _, msg := range []*Message{
		nil,
		{Payload: MessagePart{MimeType: "image/png", Body: MessagePartBody{Data: b64url("png")}}},
		{Payload: MessagePart{MimeType: "text/plain", Body: MessagePartBody{Data: "!!not base64!!"}}},
	} {
		if got := PlainBody(msg); got != "" {
			t.Errorf("want empty, got %q", got)
		}
	}
}

func TestPlainBodyIsBoundedWithoutSplittingACharacter(t *testing.T) {
	long := strings.Repeat("₹", maxBodyBytes) // 3 bytes each
	msg := &Message{Payload: MessagePart{MimeType: "text/plain", Body: MessagePartBody{Data: b64url(long)}}}

	got := PlainBody(msg)

	if len(got) > maxBodyBytes || !strings.HasSuffix(got, "₹") {
		t.Errorf("want at most %d bytes ending in a whole rune, got %d", maxBodyBytes, len(got))
	}
}
