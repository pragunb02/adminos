package handlers

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/adminos/adminos/workers/internal/gmail"
	"github.com/adminos/adminos/workers/internal/tokencrypt"
)

// fakeGoogle is a local Gmail API + token endpoint (only external clients are faked, ENGINEERING §3).
type fakeGoogle struct {
	t      *testing.T
	server *httptest.Server

	mu               sync.Mutex
	profileHistoryID string
	historyPages     map[string]string // startHistoryId → JSON page; missing = 404
	listIDs          []string          // ids returned by messages.list
	messages         map[string]gmail.Message
	failAll          int    // status to answer every Gmail call with (0 = normal)
	forbidFull       string // message ID whose full fetch answers 403
	tokenStatus      int
	tokenBody        string

	requests      []string // "GET /path?query"
	authorization []string
	fullFetches   []string // message IDs fetched with format=full
	lastListQuery string
	refreshes     []string // refresh tokens presented
}

func newFakeGoogle(t *testing.T) *fakeGoogle {
	f := &fakeGoogle{t: t, historyPages: map[string]string{}, messages: map[string]gmail.Message{},
		profileHistoryID: "5000", tokenStatus: 200, tokenBody: `{"access_token":"refreshed-access","expires_in":3599}`}
	f.server = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(f.server.Close)
	return f
}

func (f *fakeGoogle) client() *gmail.Client {
	return gmail.NewClientWithEndpoints(f.server.URL, f.server.URL+"/token", gmail.RetryPolicy{Attempts: 2, BaseDelay: time.Millisecond})
}

// addMessage adds a message as Gmail delivers genuine mail: Gmail's own Authentication-Results on
// top, with a DMARC pass for the From domain (AOS-624).
func (f *fakeGoogle) addMessage(id, from, subject, date string, body gmail.MessagePart) {
	f.addMessageWithHeaders(id, body,
		gmail.MessageHeader{Name: "Authentication-Results", Value: genuineAuthResults(from)},
		gmail.MessageHeader{Name: "From", Value: from}, gmail.MessageHeader{Name: "Subject", Value: subject},
		gmail.MessageHeader{Name: "Date", Value: date})
}

// addMessageWithHeaders adds a message with exactly these headers, in this order.
func (f *fakeGoogle) addMessageWithHeaders(id string, body gmail.MessagePart, headers ...gmail.MessageHeader) {
	body.Headers = headers
	f.messages[id] = gmail.Message{ID: id, Snippet: "snippet of " + id, Payload: body}
}

// genuineAuthResults is what Gmail writes for mail that passes DMARC as the From domain.
func genuineAuthResults(from string) string {
	domain := from[strings.LastIndexByte(from, '@')+1:]
	domain = strings.ToLower(strings.TrimRight(domain, "> "))
	return "mx.google.com; dkim=pass header.i=@" + domain + " header.s=s1; spf=pass (google.com: domain of alerts@" +
		domain + " designates 192.0.2.1 as permitted sender) smtp.mailfrom=alerts@" + domain +
		"; dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=" + domain
}

func textPart(s string) gmail.MessagePart {
	return gmail.MessagePart{MimeType: "text/plain", Body: gmail.MessagePartBody{Data: base64.RawURLEncoding.EncodeToString([]byte(s))}}
}

func htmlPart(s string) gmail.MessagePart {
	return gmail.MessagePart{MimeType: "text/html", Body: gmail.MessagePartBody{Data: base64.RawURLEncoding.EncodeToString([]byte(s))}}
}

func (f *fakeGoogle) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.requests = append(f.requests, r.Method+" "+r.URL.Path+"?"+r.URL.RawQuery)
	if r.URL.Path == "/token" {
		_ = r.ParseForm()
		f.refreshes = append(f.refreshes, r.FormValue("refresh_token"))
		w.WriteHeader(f.tokenStatus)
		w.Write([]byte(f.tokenBody))
		return
	}
	f.authorization = append(f.authorization, r.Header.Get("Authorization"))
	if f.failAll != 0 {
		w.WriteHeader(f.failAll)
		return
	}
	q := r.URL.Query()
	switch {
	case r.URL.Path == "/gmail/v1/users/me/profile":
		writeJSON(w, map[string]string{"emailAddress": "owner@example.com", "historyId": f.profileHistoryID})
	case r.URL.Path == "/gmail/v1/users/me/history":
		page, ok := f.historyPages[q.Get("startHistoryId")+"|"+q.Get("pageToken")]
		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Write([]byte(page))
	case r.URL.Path == "/gmail/v1/users/me/messages":
		f.lastListQuery = q.Get("q")
		var entries []gmail.MessageListEntry
		for _, id := range f.listIDs {
			entries = append(entries, gmail.MessageListEntry{ID: id})
		}
		writeJSON(w, gmail.MessageListResponse{Messages: entries})
	case strings.HasPrefix(r.URL.Path, "/gmail/v1/users/me/messages/"):
		id := strings.TrimPrefix(r.URL.Path, "/gmail/v1/users/me/messages/")
		msg, ok := f.messages[id]
		if !ok {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if q.Get("format") == "metadata" {
			writeJSON(w, gmail.Message{ID: msg.ID, LabelIDs: msg.LabelIDs, Payload: gmail.MessagePart{Headers: onlyHeaders(msg.Payload.Headers, q["metadataHeaders"])}})
			return
		}
		if id == f.forbidFull {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		f.fullFetches = append(f.fullFetches, id)
		writeJSON(w, msg)
	default:
		w.WriteHeader(http.StatusTeapot)
	}
}

// onlyHeaders is what Gmail's metadata format returns: the named headers, in message order.
func onlyHeaders(all []gmail.MessageHeader, names []string) []gmail.MessageHeader {
	var out []gmail.MessageHeader
	for _, h := range all {
		for _, n := range names {
			if strings.EqualFold(h.Name, n) {
				out = append(out, h)
				break
			}
		}
	}
	return out
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

// memConnections is the in-memory gmailConnectionStore.
type memConnections struct {
	mu       sync.Mutex
	conn     *storedConnection
	saved    []tokenUpdate
	revoked  string
	failures []syncFailure
	saveErr  error
	started  []string // sync sessions marked in_progress
	startErr error
}

func (m *memConnections) MarkStarted(_ context.Context, syncSessionID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.started = append(m.started, syncSessionID)
	return m.startErr
}

func (m *memConnections) Load(_ context.Context, _ string) (*storedConnection, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.conn == nil {
		return nil, errConnectionNotFound
	}
	c := *m.conn
	return &c, nil
}

func (m *memConnections) SaveTokens(_ context.Context, _ string, u tokenUpdate) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.saveErr != nil {
		return m.saveErr
	}
	m.saved = append(m.saved, u)
	if u.AccessToken != nil {
		m.conn.AccessToken = *u.AccessToken
	}
	if u.RefreshToken != nil {
		m.conn.RefreshToken = *u.RefreshToken
	}
	if u.ExpiresAt != nil {
		m.conn.ExpiresAt = u.ExpiresAt
	}
	return nil
}

func (m *memConnections) MarkRevoked(_ context.Context, _ string, reason string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.revoked = reason
	m.conn.Status = "error"
	return nil
}

func (m *memConnections) RecordFailure(_ context.Context, f syncFailure) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.failures = append(m.failures, f)
	return nil
}

// testCipher is a cipher with a key generated for this test run (never a literal key).
func testCipher(t *testing.T) *tokencrypt.Cipher {
	t.Helper()
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	c, err := tokencrypt.New(base64.StdEncoding.EncodeToString(b))
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func seal(t *testing.T, c *tokencrypt.Cipher, plain, connID, field string) string {
	t.Helper()
	s, err := c.Encrypt(plain, tokencrypt.ConnectionContext(connID, field))
	if err != nil {
		t.Fatal(err)
	}
	return s
}
