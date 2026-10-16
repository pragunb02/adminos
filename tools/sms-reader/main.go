// Command sms-reader forwards bank SMS from macOS Messages to AdminOS (D-35).
//
// The iPhone copies SMS to the Mac (Text Message Forwarding). This reader opens
// ~/Library/Messages/chat.db read-only, keeps business senders only (personal chats never
// leave the Mac), and posts them to the local AdminOS API with a device token. It never
// logs or stores message text.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type config struct {
	dbPath, apiURL, tokenFile, stateFile string
	interval                             time.Duration
	backfillDays                         int
	once                                 bool
}

func main() {
	home, _ := os.UserHomeDir()
	support := filepath.Join(home, "Library", "Application Support", "AdminOS")
	var c config
	flag.StringVar(&c.dbPath, "db", filepath.Join(home, "Library", "Messages", "chat.db"), "Messages database (read-only)")
	flag.StringVar(&c.apiURL, "api", "http://127.0.0.1:8080", "AdminOS API base URL (localhost only)")
	flag.StringVar(&c.tokenFile, "token-file", filepath.Join(support, "device-token"), "file holding the device token (mode 0600)")
	flag.StringVar(&c.stateFile, "state", filepath.Join(support, "sms-reader.state"), "last forwarded message id")
	flag.DurationVar(&c.interval, "interval", 30*time.Second, "how often to check for new messages")
	flag.IntVar(&c.backfillDays, "backfill-days", 0, "on first run, also send the last N days of SMS (0 = only new)")
	flag.BoolVar(&c.once, "once", false, "run one pass and exit")
	flag.Parse()

	if err := run(c); err != nil {
		log.Fatalf("sms-reader: %v", err)
	}
}

func run(c config) error {
	if err := requireLocalAPI(c.apiURL); err != nil {
		return err
	}
	token, err := readToken(c.tokenFile)
	if err != nil {
		return err
	}
	db, err := openMessagesDB(c.dbPath)
	if err != nil {
		return err
	}
	defer db.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	hc := &http.Client{Timeout: 30 * time.Second}

	last, err := initialState(ctx, c, db)
	if err != nil {
		return err
	}
	log.Printf("sms-reader started (from message id %d)", last)

	for {
		next, err := pass(ctx, c, db, hc, token, last)
		if errors.Is(err, errUnauthorized) {
			return err
		}
		if err != nil {
			log.Printf("pass failed, will retry: %v", err) // never contains message text
		}
		if next != last {
			last = next
			if err := writeState(c.stateFile, last); err != nil {
				return err
			}
		}
		if c.once {
			return nil
		}
		select {
		case <-ctx.Done():
			log.Printf("sms-reader stopped")
			return nil
		case <-time.After(c.interval):
		}
	}
}

// pass forwards everything after `after`. It returns the new position only for batches the API
// accepted, so failures are retried next time and nothing is skipped.
func pass(ctx context.Context, c config, db sqlDB, hc *http.Client, token string, after int64) (int64, error) {
	pos := after
	for {
		msgs, scanned, err := fetchAfter(ctx, db, pos, 100)
		if err != nil {
			return pos, err
		}
		if scanned == pos {
			return pos, nil // nothing new
		}
		var business []SMS
		for _, m := range msgs {
			if isBusinessSender(m.Sender) {
				business = append(business, m)
			}
		}
		if len(business) > 0 {
			r, err := postBatch(ctx, hc, c.apiURL, token, business)
			if err != nil {
				return pos, err
			}
			log.Printf("forwarded %d SMS: %d new, %d duplicate, %d ignored", len(business), r.Accepted, r.Duplicates, r.Ignored)
		}
		pos = scanned
	}
}

func initialState(ctx context.Context, c config, db sqlDB) (int64, error) {
	if b, err := os.ReadFile(c.stateFile); err == nil {
		return strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64)
	} else if !errors.Is(err, os.ErrNotExist) {
		return 0, fmt.Errorf("read state: %w", err)
	}
	var start int64
	var err error
	if c.backfillDays > 0 {
		start, err = rowIDBefore(ctx, db, time.Now().AddDate(0, 0, -c.backfillDays))
	} else {
		start, err = maxRowID(ctx, db)
	}
	if err != nil {
		return 0, fmt.Errorf("find starting point (does this binary have Full Disk Access?): %w", err)
	}
	return start, writeState(c.stateFile, start)
}

func writeState(path string, id int64) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(strconv.FormatInt(id, 10)), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// readToken refuses tokens other users could read.
func readToken(path string) (string, error) {
	info, err := os.Stat(path)
	if err != nil {
		return "", fmt.Errorf("device token: %w (create one in AdminOS Settings and save it to %s)", err, path)
	}
	if info.Mode().Perm()&0o077 != 0 {
		return "", fmt.Errorf("device token file %s is readable by other users; run: chmod 600 %q", path, path)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	t := strings.TrimSpace(string(b))
	if strings.Count(t, ".") != 2 {
		return "", fmt.Errorf("device token in %s doesn't look like a token", path)
	}
	return t, nil
}

// requireLocalAPI: this reader only ever talks to AdminOS on this machine.
func requireLocalAPI(raw string) error {
	for _, ok := range []string{"http://127.0.0.1:", "http://localhost:", "http://[::1]:"} {
		if strings.HasPrefix(raw, ok) {
			return nil
		}
	}
	return fmt.Errorf("-api must be a localhost URL (got %q): SMS must not leave this Mac", raw)
}
