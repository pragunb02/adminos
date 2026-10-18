package main

import (
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// typedstream builds an NSAttributedString-like blob the way Messages stores text on recent macOS.
func typedstream(text string) []byte {
	b := []byte("\x04\x0bstreamtyped\x81\xe8\x03\x84\x01@\x84\x84\x84\x12NSAttributedString\x00\x84\x84\x08NSObject\x00\x85\x92\x84\x84\x84\x08NSString\x01\x94\x84\x01+")
	if n := len(text); n < 0x80 {
		b = append(b, byte(n))
	} else {
		b = append(b, 0x81, 0, 0)
		binary.LittleEndian.PutUint16(b[len(b)-2:], uint16(n))
	}
	b = append(b, text...)
	return append(b, []byte("\x86\x84\x02iI\x01")...)
}

func TestDecodeAttributedBody(t *testing.T) {
	short := "Sent Rs.249.00 To SWIGGY"
	long := strings.Repeat("Rs.1 debited ", 20) // > 127 bytes → 0x81 uint16 length
	for _, want := range []string{short, long[:len(long)-1]} {
		if got := decodeAttributedBody(typedstream(want)); got != strings.TrimSpace(want) {
			t.Fatalf("decode: got %q want %q", got, want)
		}
	}
	for _, junk := range [][]byte{nil, []byte("no marker"), []byte("NSString"), []byte("NSString\x01+\x7f")} {
		if got := decodeAttributedBody(junk); got != "" {
			t.Fatalf("junk decoded to %q", got)
		}
	}
}

func TestAppleTime(t *testing.T) {
	want := time.Date(2026, 10, 1, 3, 42, 0, 0, time.UTC)
	if got := appleTime(toAppleTime(want)); !got.Equal(want) {
		t.Fatalf("nanoseconds: got %s", got)
	}
	if got := appleTime(int64(want.Sub(appleEpoch).Seconds())); !got.Equal(want) {
		t.Fatalf("seconds (old macOS): got %s", got)
	}
}

func TestIsBusinessSender(t *testing.T) {
	for _, s := range []string{"AX-HDFCBK-S", "VM-HDFCBK", "HDFCBK", "AD-SBIUPI-T"} {
		if !isBusinessSender(s) {
			t.Errorf("%q should be a business sender", s)
		}
	}
	for _, s := range []string{"+919876543210", "9876543210", "friend@example.com", "", "Mom", "MOM", "ad-sbiupi-t", "Raj Kumar"} {
		if isBusinessSender(s) {
			t.Errorf("%q must not be forwarded", s)
		}
	}
}

func TestSMSStringRedactsText(t *testing.T) {
	if s := (SMS{Text: "secret-merchant"}).String(); strings.Contains(s, "secret") {
		t.Fatal(s)
	}
}

// fakeMessagesDB creates a chat.db with the columns the reader uses, then reopens it read-only.
func fakeMessagesDB(t *testing.T) (string, *sql.DB) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "chat.db")
	w, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	at := toAppleTime(time.Date(2026, 10, 1, 3, 42, 0, 0, time.UTC))
	stmts := []string{
		`CREATE TABLE handle (ROWID INTEGER PRIMARY KEY, id TEXT)`,
		`CREATE TABLE message (ROWID INTEGER PRIMARY KEY, handle_id INTEGER, text TEXT, attributedBody BLOB, date INTEGER, is_from_me INTEGER, service TEXT)`,
		`INSERT INTO handle VALUES (1, 'AX-HDFCBK-S'), (2, '+919876543210'), (3, 'friend@example.com'), (4, 'VM-ICICIT-S')`,
	}
	for _, s := range stmts {
		if _, err := w.Exec(s); err != nil {
			t.Fatal(err)
		}
	}
	ins := `INSERT INTO message (ROWID, handle_id, text, attributedBody, date, is_from_me, service) VALUES (?, ?, ?, ?, ?, ?, ?)`
	rows := [][]any{
		{1, 1, "Sent Rs.249.00 To SWIGGY", nil, at, 0, "SMS"},               // bank, text column
		{2, 4, nil, typedstream("INR 99.00 spent on AMAZON"), at, 0, "SMS"}, // bank, attributedBody only
		{3, 2, "hey, dinner tonight?", nil, at, 0, "SMS"},                   // personal SMS → filtered
		{4, 3, "personal iMessage", nil, at, 0, "iMessage"},                 // iMessage → not selected
		{5, 1, "outgoing", nil, at, 1, "SMS"},                               // from me → not selected
	}
	for _, r := range rows {
		if _, err := w.Exec(ins, r...); err != nil {
			t.Fatal(err)
		}
	}
	w.Close()
	db, err := openMessagesDB(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return path, db
}

func TestDatabaseIsOpenedReadOnly(t *testing.T) {
	_, db := fakeMessagesDB(t)
	if _, err := db.Exec(`DELETE FROM message`); err == nil {
		t.Fatal("write succeeded on the Messages database; it must be read-only")
	}
}

type fakeAPI struct {
	mu      sync.Mutex
	batches [][]rawSMS
	auth    []string
	status  int
}

func (f *fakeAPI) handler(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.auth = append(f.auth, r.Header.Get("Authorization"))
	if f.status != 0 && f.status != http.StatusAccepted {
		w.WriteHeader(f.status)
		return
	}
	var body struct{ Messages []rawSMS }
	_ = json.NewDecoder(r.Body).Decode(&body)
	f.batches = append(f.batches, body.Messages)
	w.WriteHeader(http.StatusAccepted)
	_, _ = w.Write([]byte(`{"success":true,"data":{"accepted":2,"duplicates":0,"ignored":0}}`))
}

func TestPassForwardsOnlyBankSMSAndAdvances(t *testing.T) {
	_, db := fakeMessagesDB(t)
	api := &fakeAPI{}
	srv := httptest.NewServer(http.HandlerFunc(api.handler))
	defer srv.Close()

	pos, err := pass(context.Background(), config{apiURL: srv.URL}, db, srv.Client(), "a.b.c", 0)
	if err != nil {
		t.Fatal(err)
	}
	if pos != 3 {
		t.Fatalf("position: got %d want 3 (last incoming SMS row)", pos)
	}
	if len(api.batches) != 1 || len(api.batches[0]) != 2 {
		t.Fatalf("want one batch with the 2 bank SMS, got %+v", api.batches)
	}
	got := api.batches[0]
	if got[0].Sender != "AX-HDFCBK-S" || got[1].Text != "INR 99.00 spent on AMAZON" || got[0].ReceivedAt != "2026-10-01T03:42:00Z" {
		t.Fatalf("unexpected payload %+v", got)
	}
	for _, m := range got {
		if strings.Contains(m.Text, "dinner") {
			t.Fatal("personal SMS was forwarded")
		}
	}
	if api.auth[0] != "Bearer a.b.c" {
		t.Fatalf("auth header %q", api.auth[0])
	}

	// Nothing new → no request, same position.
	pos2, _ := pass(context.Background(), config{apiURL: srv.URL}, db, srv.Client(), "a.b.c", pos)
	if pos2 != pos || len(api.batches) != 1 {
		t.Fatal("second pass should be a no-op")
	}
}

func TestPassKeepsPositionWhenTheAPIFails(t *testing.T) {
	_, db := fakeMessagesDB(t)
	api := &fakeAPI{status: http.StatusServiceUnavailable}
	srv := httptest.NewServer(http.HandlerFunc(api.handler))
	defer srv.Close()
	pos, err := pass(context.Background(), config{apiURL: srv.URL}, db, srv.Client(), "a.b.c", 0)
	if err == nil || pos != 0 {
		t.Fatalf("want error and unchanged position, got pos=%d err=%v", pos, err)
	}
	api.status = http.StatusUnauthorized
	if _, err := pass(context.Background(), config{apiURL: srv.URL}, db, srv.Client(), "a.b.c", 0); !errors.Is(err, errUnauthorized) {
		t.Fatalf("want errUnauthorized, got %v", err)
	}
}

func TestInitialStateStartsAtNewMessagesOrBackfills(t *testing.T) {
	_, db := fakeMessagesDB(t)
	dir := t.TempDir()
	c := config{stateFile: filepath.Join(dir, "state")}
	if pos, _ := initialState(context.Background(), c, db); pos != 5 {
		t.Fatalf("default should start after the newest message, got %d", pos)
	}
	c2 := config{stateFile: filepath.Join(dir, "state2"), backfillDays: 3650}
	if pos, _ := initialState(context.Background(), c2, db); pos != 0 {
		t.Fatalf("backfill should start before old messages, got %d", pos)
	}
	if pos, _ := initialState(context.Background(), c, db); pos != 5 {
		t.Fatal("saved state should be reused")
	}
}

func TestReadTokenRefusesFilesOthersCanRead(t *testing.T) {
	p := filepath.Join(t.TempDir(), "device-token")
	_ = os.WriteFile(p, []byte("a.b.c\n"), 0o644)
	if _, err := readToken(p); err == nil || !strings.Contains(err.Error(), "chmod 600") {
		t.Fatalf("want permission error, got %v", err)
	}
	_ = os.Chmod(p, 0o600)
	if tok, err := readToken(p); err != nil || tok != "a.b.c" {
		t.Fatalf("got %q %v", tok, err)
	}
}

func TestOnlyLocalhostAPIIsAllowed(t *testing.T) {
	for _, ok := range []string{"http://127.0.0.1:8080", "http://localhost:3000"} {
		if requireLocalAPI(ok) != nil {
			t.Errorf("%s should be allowed", ok)
		}
	}
	for _, bad := range []string{"https://example.com", "http://192.168.1.5:8080", "http://127.0.0.1.evil.com:80"} {
		if requireLocalAPI(bad) == nil {
			t.Errorf("%s must be refused", bad)
		}
	}
}
