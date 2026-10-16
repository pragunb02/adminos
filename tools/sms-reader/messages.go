package main

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/binary"
	"fmt"
	"net/url"
	"regexp"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// SMS is one incoming message read from the Messages database. Text stays in memory only (D-33).
type SMS struct {
	RowID      int64
	Sender     string
	Text       string
	ReceivedAt time.Time
}

// String never prints the message text, so logs can't leak it.
func (s SMS) String() string {
	return fmt.Sprintf("SMS{row=%d sender=%s at=%s text=<%d chars>}", s.RowID, s.Sender, s.ReceivedAt.Format(time.RFC3339), len(s.Text))
}

// openMessagesDB opens chat.db strictly read-only (we never write to the user's Messages database).
func openMessagesDB(path string) (*sql.DB, error) {
	dsn := "file:" + (&url.URL{Path: path}).EscapedPath() + "?mode=ro&_pragma=query_only(1)&_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open messages db: %w", err)
	}
	db.SetMaxOpenConns(1)
	return db, nil
}

// fetchAfter returns incoming SMS/RCS rows with ROWID > after, oldest first.
func fetchAfter(ctx context.Context, db sqlDB, after int64, limit int) ([]SMS, int64, error) {
	rows, err := db.QueryContext(ctx, `
		SELECT m.ROWID, COALESCE(h.id, ''), m.text, m.attributedBody, m.date
		FROM message m LEFT JOIN handle h ON h.ROWID = m.handle_id
		WHERE m.ROWID > ? AND m.is_from_me = 0 AND m.service IN ('SMS', 'RCS')
		ORDER BY m.ROWID LIMIT ?`, after, limit)
	if err != nil {
		return nil, after, fmt.Errorf("query messages: %w", err)
	}
	defer rows.Close()

	var out []SMS
	last := after
	for rows.Next() {
		var (
			id       int64
			sender   string
			text     sql.NullString
			body     []byte
			appleRaw int64
		)
		if err := rows.Scan(&id, &sender, &text, &body, &appleRaw); err != nil {
			return nil, last, fmt.Errorf("scan message: %w", err)
		}
		last = id
		t := strings.TrimSpace(text.String)
		if t == "" {
			t = decodeAttributedBody(body)
		}
		if t == "" {
			continue
		}
		out = append(out, SMS{RowID: id, Sender: sender, Text: t, ReceivedAt: appleTime(appleRaw)})
	}
	return out, last, rows.Err()
}

// maxRowID is where to start when there is no saved state (only new messages from now on).
func maxRowID(ctx context.Context, db sqlDB) (int64, error) {
	var id sql.NullInt64
	err := db.QueryRowContext(ctx, `SELECT MAX(ROWID) FROM message`).Scan(&id)
	return id.Int64, err
}

// rowIDBefore is the last ROWID received before t, for backfilling history.
func rowIDBefore(ctx context.Context, db sqlDB, t time.Time) (int64, error) {
	var id sql.NullInt64
	err := db.QueryRowContext(ctx, `SELECT MAX(ROWID) FROM message WHERE date < ?`, toAppleTime(t)).Scan(&id)
	return id.Int64, err
}

var appleEpoch = time.Date(2001, 1, 1, 0, 0, 0, 0, time.UTC)

// appleTime converts Messages' timestamp: nanoseconds since 2001-01-01 (seconds on old macOS).
func appleTime(v int64) time.Time {
	if v > 1_000_000_000_000 {
		return appleEpoch.Add(time.Duration(v))
	}
	return appleEpoch.Add(time.Duration(v) * time.Second)
}

func toAppleTime(t time.Time) int64 { return t.Sub(appleEpoch).Nanoseconds() }

// decodeAttributedBody extracts the plain string from the NSAttributedString "typedstream" that
// recent macOS stores instead of the text column. Layout after the "NSString" class name:
// a few bytes, then '+' (0x2B), a length, and the UTF-8 bytes. Lengths < 0x80 are one byte;
// 0x81 is followed by a uint16 LE, 0x82 by a uint32 LE.
func decodeAttributedBody(b []byte) string {
	i := bytes.Index(b, []byte("NSString"))
	if i < 0 {
		return ""
	}
	rest := b[i+len("NSString"):]
	j := bytes.IndexByte(rest, 0x2B)
	if j < 0 || j+1 >= len(rest) {
		return ""
	}
	p := rest[j+1:]
	var n, hdr int
	switch p[0] {
	case 0x81:
		if len(p) < 3 {
			return ""
		}
		n, hdr = int(binary.LittleEndian.Uint16(p[1:3])), 3
	case 0x82:
		if len(p) < 5 {
			return ""
		}
		n, hdr = int(binary.LittleEndian.Uint32(p[1:5])), 5
	default:
		n, hdr = int(p[0]), 1
	}
	if n <= 0 || hdr+n > len(p) {
		return ""
	}
	return strings.TrimSpace(string(p[hdr : hdr+n]))
}

// TRAI business sender, as Messages stores it: optional 2-letter operator prefix, an UPPERCASE
// 5-9 character header (DLT headers are 6), optional category. This is the privacy gate: phone
// numbers, emails and anything name-like don't match, so personal chats never leave the Mac.
var businessSender = regexp.MustCompile(`^(?:[A-Z]{2}-)?[A-Z0-9]{5,9}(?:-[STPG])?$`)

func isBusinessSender(s string) bool {
	s = strings.TrimSpace(s)
	digits := 0
	for _, r := range s {
		if r >= '0' && r <= '9' {
			digits++
		}
	}
	return digits < 7 && businessSender.MatchString(s)
}
