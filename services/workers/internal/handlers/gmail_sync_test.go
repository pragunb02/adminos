package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/adminos/adminos/workers/internal/gmail"
	"github.com/adminos/adminos/workers/internal/tokencrypt"
)

const (
	testUser    = "8f14e45f-ceea-467a-9575-0a4b3c2d1e0f"
	testConn    = "c9f0f895-fb98-4b9a-b3c0-3d2a1b0c9d8e"
	testSession = "45c48cce-2e2d-4fbd-8a1e-6c5b4a3f2e1d"
	hdfc        = "HDFC Bank InstaAlerts <alerts@hdfcbank.net>"
	alertDate   = "Fri, 16 Oct 2026 09:12:00 +0530"
)

var testNow = time.Date(2026, 10, 17, 6, 0, 0, 0, time.UTC)

type syncFixture struct {
	google *fakeGoogle
	conns  *memConnections
	cipher *tokencrypt.Cipher
	h      *GmailHandler
}

// newSyncFixture: a connected Gmail connection with encrypted tokens valid for an hour.
func newSyncFixture(t *testing.T, historyID string) *syncFixture {
	t.Helper()
	google := newFakeGoogle(t)
	cipher := testCipher(t)
	exp := testNow.Add(time.Hour)
	conns := &memConnections{conn: &storedConnection{
		Status:       "connected",
		AccessToken:  seal(t, cipher, "current-access", testConn, "access_token"),
		RefreshToken: seal(t, cipher, "current-refresh", testConn, "refresh_token"),
		ExpiresAt:    &exp,
		HistoryID:    historyID,
	}}
	h := newGmailHandler(nil, conns, google.client(), GmailConfig{ClientID: "cid", ClientSecret: "cs", Cipher: cipher, Now: func() time.Time { return testNow }})
	return &syncFixture{google: google, conns: conns, cipher: cipher, h: h}
}

func (f *syncFixture) run(t *testing.T) (*GmailResult, error) {
	t.Helper()
	res, err := f.h.Execute(context.Background(), &GmailPayload{UserID: testUser, ConnectionID: testConn, SyncSessionID: testSession})
	if err != nil {
		return nil, err
	}
	return res.(*GmailResult), nil
}

func (f *syncFixture) mustRun(t *testing.T) *GmailResult {
	t.Helper()
	r, err := f.run(t)
	if err != nil {
		t.Fatalf("sync failed: %v", err)
	}
	return r
}

func historyPage(historyID string, ids ...string) string {
	type added struct {
		Message gmail.MessageListEntry `json:"message"`
	}
	var adds []added
	for _, id := range ids {
		adds = append(adds, added{Message: gmail.MessageListEntry{ID: id}})
	}
	b, _ := json.Marshal(map[string]any{"history": []map[string]any{{"messagesAdded": adds}}, "historyId": historyID})
	return string(b)
}

func (f *syncFixture) requested(fragment string) bool {
	for _, r := range f.google.requests {
		if strings.Contains(r, fragment) {
			return true
		}
	}
	return false
}

// --- Incremental sync and the real history ID ---

func TestIncrementalSyncStartsAtTheStoredHistoryIDAndStoresGmailsNewOne(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.historyPages["1000|"] = historyPage("1042", "m1")
	f.google.addMessage("m1", hdfc, "Transaction alert", alertDate, textPart("Rs 349.00 has been debited from your A/c XX1234 at ZOMATO"))

	r := f.mustRun(t)

	if r.NewHistoryID != "1042" || r.SyncMode != "incremental" {
		t.Errorf("want history 1042 from Gmail (incremental), got %q (%s)", r.NewHistoryID, r.SyncMode)
	}
	if strings.HasPrefix(r.NewHistoryID, "history_") {
		t.Error("the history ID must be Gmail's, never made up")
	}
	if len(r.Events) != 1 || r.Events[0].Amount != "349.00" || r.Events[0].Direction != "debit" || r.Events[0].SourceRef != "m1" {
		t.Errorf("events: %+v", r.Events)
	}
	if f.requested("/profile") || f.requested("/messages?") {
		t.Error("an incremental sync must not list or search the mailbox")
	}
}

func TestIncrementalSyncFollowsHistoryPagesAndDeduplicatesMessages(t *testing.T) {
	f := newSyncFixture(t, "1000")
	page1 := historyPage("1010", "m1", "m1")
	page1 = strings.Replace(page1, `"historyId"`, `"nextPageToken":"p2","historyId"`, 1)
	f.google.historyPages["1000|"] = page1
	f.google.historyPages["1000|p2"] = historyPage("1020", "m2", "m1")
	f.google.addMessage("m1", hdfc, "Alert", alertDate, textPart("Rs 100 has been debited from your A/c XX1234"))
	f.google.addMessage("m2", hdfc, "Alert", alertDate, textPart("Rs 200 has been credited to your A/c XX1234"))

	r := f.mustRun(t)

	if r.NewHistoryID != "1020" || r.TotalEmails != 2 || len(f.google.fullFetches) != 2 {
		t.Errorf("want 2 emails and history 1020, got %d emails, %d fetches, history %q", r.TotalEmails, len(f.google.fullFetches), r.NewHistoryID)
	}
}

func TestIncrementalSyncWithNothingNewKeepsTheHistoryIDGmailReturns(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.historyPages["1000|"] = `{"historyId":"1001"}`

	r := f.mustRun(t)

	if r.NewHistoryID != "1001" || r.TotalEmails != 0 {
		t.Errorf("got history %q, %d emails", r.NewHistoryID, r.TotalEmails)
	}
}

// --- Sender filters: personal mail is never read ---

func TestPersonalMailIsNeverFetchedInFull(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.historyPages["1000|"] = historyPage("1003", "bank", "friend", "spam")
	f.google.addMessage("bank", hdfc, "Transaction alert", alertDate, textPart("Rs 500 has been debited from your A/c XX1234"))
	f.google.addMessage("friend", "Ravi <ravi@gmail.com>", "Dinner on Friday?", alertDate, textPart("Rs 500 paid for dinner, you owe me"))
	spam := f.google.messages
	f.google.addMessage("spam", "Fake <alerts@hdfcbank.net>", "Transaction alert", alertDate, textPart("Rs 9 debited"))
	m := spam["spam"]
	m.LabelIDs = []string{"SPAM"}
	spam["spam"] = m

	r := f.mustRun(t)

	if strings.Join(f.google.fullFetches, ",") != "bank" {
		t.Errorf("only the bank email may be fetched in full, got %v", f.google.fullFetches)
	}
	if r.NotFinancial != 2 || r.TotalEmails != 1 {
		t.Errorf("want 1 email and 2 skipped, got %d and %d", r.TotalEmails, r.NotFinancial)
	}
}

func TestFullSyncSearchesOnlyFinancialSendersInTheLast30Days(t *testing.T) {
	f := newSyncFixture(t, "")

	f.mustRun(t)

	if f.google.lastListQuery != gmail.SearchQuery(30) || !strings.HasPrefix(f.google.lastListQuery, "newer_than:30d (from:(") {
		t.Errorf("unexpected search %q", f.google.lastListQuery)
	}
}

// --- Bank mail must be authenticated (AOS-624, AUD-11) ---

// spoofedHDFC is a stranger's message claiming to be HDFC: Gmail's DMARC check failed, and the
// sender wrote a passing Authentication-Results of their own into the message (below Gmail's).
func (f *syncFixture) addSpoofedHDFC(id string) {
	f.google.addMessageWithHeaders(id, textPart("Rs 99,999.00 has been credited to your A/c XX1234"),
		gmail.MessageHeader{Name: "Authentication-Results", Value: "mx.google.com; spf=softfail smtp.mailfrom=x@evil.example; dmarc=fail (p=REJECT) header.from=hdfcbank.net"},
		gmail.MessageHeader{Name: "Authentication-Results", Value: "mx.google.com; dkim=pass header.d=hdfcbank.net; dmarc=pass header.from=hdfcbank.net"},
		gmail.MessageHeader{Name: "From", Value: hdfc},
		gmail.MessageHeader{Name: "Subject", Value: "Transaction alert"},
		gmail.MessageHeader{Name: "Date", Value: alertDate})
}

func TestIncrementalSyncNeverFetchesSpoofedBankMail(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.historyPages["1000|"] = historyPage("1002", "real", "spoof")
	f.google.addMessage("real", hdfc, "Transaction alert", alertDate, textPart("Rs 500 has been debited from your A/c XX1234"))
	f.addSpoofedHDFC("spoof")

	r := f.mustRun(t)

	if strings.Join(f.google.fullFetches, ",") != "real" {
		t.Errorf("a spoofed bank email must never be fetched in full, got %v", f.google.fullFetches)
	}
	if r.Unauthenticated != 1 || r.TotalEmails != 1 || len(r.Events) != 1 || r.Events[0].Amount != "500.00" {
		t.Errorf("want 1 real email and 1 rejected, got %d emails, %d rejected, %+v", r.TotalEmails, r.Unauthenticated, r.Events)
	}
}

func TestASubjectAloneNeverAdmitsAMessage(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.historyPages["1000|"] = historyPage("1001", "stranger")
	f.google.addMessage("stranger", "Ravi <ravi@gmail.com>", "Transaction alert: Rs 500 has been debited", alertDate,
		textPart("Rs 500 has been debited from your A/c XX1234"))

	r := f.mustRun(t)

	if len(f.google.fullFetches) != 0 || r.NotFinancial != 1 || r.TotalEmails != 0 {
		t.Errorf("a bank-alert subject from a stranger must not be read, got fetches %v, %d skipped", f.google.fullFetches, r.NotFinancial)
	}
}

func TestFullSyncQualifiesEverySearchResultLocally(t *testing.T) {
	f := newSyncFixture(t, "")
	f.google.listIDs = []string{"real", "spoof", "lookalike"}
	f.google.addMessage("real", hdfc, "Transaction alert", alertDate, textPart("Rs 500 has been debited from your A/c XX1234"))
	f.addSpoofedHDFC("spoof")
	// Gmail's from: search also matches the display name; the address is a stranger's.
	f.google.addMessage("lookalike", `"alerts@hdfcbank.net" <x@evil.example>`, "Transaction alert", alertDate,
		textPart("Rs 700 has been debited from your A/c XX1234"))

	r := f.mustRun(t)

	if r.TotalEmails != 1 || len(r.Events) != 1 || r.Events[0].SourceRef != "real" {
		t.Errorf("only the authenticated email may be parsed, got %d emails, %+v", r.TotalEmails, r.Events)
	}
	if r.Unauthenticated != 1 || r.NotFinancial != 1 {
		t.Errorf("want 1 unauthenticated and 1 not financial, got %d and %d", r.Unauthenticated, r.NotFinancial)
	}
}

// --- Fallback to a bounded full sync ---

func TestAnExpiredHistoryIDFallsBackToAFullSync(t *testing.T) {
	f := newSyncFixture(t, "1000") // no history page → Gmail answers 404
	f.google.profileHistoryID = "7777"
	f.google.listIDs = []string{"m1"}
	f.google.addMessage("m1", hdfc, "Transaction alert", alertDate, textPart("INR 75,000.00 credited to your account XX1234 by NEFT from ACME"))

	r := f.mustRun(t)

	if r.SyncMode != "full" || r.NewHistoryID != "7777" || len(r.Events) != 1 || r.Events[0].Direction != "credit" {
		t.Errorf("want a full sync to history 7777 with 1 credit, got %s %q %+v", r.SyncMode, r.NewHistoryID, r.Events)
	}
	if !f.requested("startHistoryId=1000") {
		t.Error("the stored history ID must be tried first")
	}
}

func TestAMissingOrMadeUpHistoryIDRunsAFullSync(t *testing.T) {
	for _, stored := range []string{"", "history_1760000000"} {
		f := newSyncFixture(t, stored)
		f.google.profileHistoryID = "8000"

		r := f.mustRun(t)

		if r.SyncMode != "full" || r.NewHistoryID != "8000" || f.requested("/history") {
			t.Errorf("stored %q: want a full sync without history.list, got %s %q", stored, r.SyncMode, r.NewHistoryID)
		}
	}
}

func TestAFullSyncIsCappedAndReadsTheHistoryIDFirst(t *testing.T) {
	f := newSyncFixture(t, "")
	for i := 0; i < maxFullSyncMessages+20; i++ {
		f.google.listIDs = append(f.google.listIDs, "id"+string(rune('a'+i%26))+strings.Repeat("x", i/26))
	}

	r := f.mustRun(t)

	if r.TotalEmails != 0 || r.FailedItems != maxFullSyncMessages {
		t.Errorf("want %d messages tried (all gone), got failed=%d", maxFullSyncMessages, r.FailedItems)
	}
	if !strings.Contains(f.google.requests[0], "/profile") {
		t.Errorf("the history ID must be read before listing, first request was %s", f.google.requests[0])
	}
}

func TestAProfileWithoutAHistoryIDKeepsTheStoredOne(t *testing.T) {
	f := newSyncFixture(t, "history_1")
	f.google.profileHistoryID = ""

	if r := f.mustRun(t); r.NewHistoryID != "history_1" {
		t.Errorf("got %q", r.NewHistoryID)
	}
}

// --- Full body, not the snippet ---

func TestTheFullBodyIsParsedNotTheSnippet(t *testing.T) {
	f := newSyncFixture(t, "")
	f.google.listIDs = []string{"plain", "html"}
	f.google.addMessage("plain", hdfc, "Alert", alertDate, gmail.MessagePart{MimeType: "multipart/alternative", Parts: []gmail.MessagePart{
		textPart("Dear Customer, Rs 1,250.00 has been debited from your A/c XX1234 to VPA swiggy@icici on 16-10-26."),
		htmlPart("<p>ignored</p>"),
	}})
	f.google.addMessage("html", "ICICI Bank <credit_cards@icicibank.com>", "Alert", alertDate,
		htmlPart("<table><tr><td>Amount</td><td>INR 2,499.00</td></tr></table><p>spent on your ICICI Bank Credit Card XX9876 at AMAZON</p>"))

	r := f.mustRun(t)

	if len(r.Events) != 2 {
		t.Fatalf("want 2 events from the bodies, got %+v", r.Events)
	}
	if r.Events[0].Amount != "1250.00" || r.Events[1].Amount != "2499.00" || r.Events[1].Direction != "debit" {
		t.Errorf("events: %+v / %+v", r.Events[0], r.Events[1])
	}
}

func TestAMessageWithoutReadableTextFallsBackToItsSnippet(t *testing.T) {
	f := newSyncFixture(t, "")
	f.google.listIDs = []string{"img"}
	f.google.addMessage("img", hdfc, "Alert", alertDate, gmail.MessagePart{MimeType: "image/png"})
	m := f.google.messages["img"]
	m.Snippet = "Rs 75 has been debited from your A/c XX1234"
	f.google.messages["img"] = m

	if r := f.mustRun(t); len(r.Events) != 1 || r.Events[0].Amount != "75.00" {
		t.Errorf("events: %+v", r.Events)
	}
}

func TestEmailContentIsNeverSerialised(t *testing.T) {
	b, _ := json.Marshal(GmailEmail{MessageID: "m", From: "a@b.c", Subject: "s", Body: "Rs 500 debited", Date: "d"})
	if string(b) != "{}" {
		t.Errorf("GmailEmail must not serialise its content, got %s", b)
	}
}

// --- Gmail errors ---

func TestAMessageDeletedBeforeItIsFetchedIsSkipped(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.historyPages["1000|"] = historyPage("1002", "gone", "m1")
	f.google.addMessage("m1", hdfc, "Alert", alertDate, textPart("Rs 10 has been debited from your A/c XX1234"))

	r := f.mustRun(t)

	if r.FailedItems != 1 || len(r.Events) != 1 {
		t.Errorf("want 1 gone and 1 event, got failed=%d events=%d", r.FailedItems, len(r.Events))
	}
}

func TestUnreachableGmailFailsTheJobAndRecordsWhy(t *testing.T) {
	f := newSyncFixture(t, "1000")
	f.google.failAll = 503

	_, err := f.run(t)

	var retryable *gmail.RetryableError
	if !errors.As(err, &retryable) {
		t.Fatalf("want a retryable error (job retried, D-43), got %v", err)
	}
	if len(f.conns.failures) != 1 || f.conns.failures[0] != (syncFailure{ConnectionID: testConn, SyncSessionID: testSession, Reason: "gmail temporarily unavailable"}) {
		t.Errorf("failures: %+v", f.conns.failures)
	}
	if len(f.google.requests) != 2 {
		t.Errorf("want the call retried once (2 attempts), got %d requests", len(f.google.requests))
	}
}

func TestAFailedMessageFetchFailsTheJob(t *testing.T) {
	f := newSyncFixture(t, "")
	f.google.listIDs = []string{"m1"}
	f.google.addMessage("m1", hdfc, "Alert", alertDate, textPart("x"))
	f.google.forbidFull = "m1"

	if _, err := f.run(t); err == nil {
		t.Fatal("want an error")
	}
	if f.conns.failures[0].Reason != "gmail sync failed" {
		t.Errorf("reason %q", f.conns.failures[0].Reason)
	}
}

// --- Tokens (encrypted at rest, D-59) ---

func TestStoredTokensAreDecryptedBeforeUse(t *testing.T) {
	f := newSyncFixture(t, "")

	f.mustRun(t)

	if f.google.authorization[0] != "Bearer current-access" {
		t.Errorf("Gmail must get the decrypted token, got %q", f.google.authorization[0])
	}
	if len(f.conns.saved) != 0 || len(f.google.refreshes) != 0 {
		t.Error("a valid encrypted token needs no refresh and no write")
	}
}

func TestAnExpiringTokenIsRefreshedAndStoredEncrypted(t *testing.T) {
	f := newSyncFixture(t, "")
	soon := testNow.Add(4 * time.Minute)
	f.conns.conn.ExpiresAt = &soon

	f.mustRun(t)

	if strings.Join(f.google.refreshes, ",") != "current-refresh" || f.google.authorization[0] != "Bearer refreshed-access" {
		t.Fatalf("refresh with the decrypted refresh token, then use the new one: %v %v", f.google.refreshes, f.google.authorization)
	}
	saved := f.conns.saved[0]
	plain, legacy, err := f.cipher.Decrypt(*saved.AccessToken, tokencrypt.ConnectionContext(testConn, "access_token"))
	if err != nil || legacy || plain != "refreshed-access" {
		t.Errorf("the new access token must be stored v2-encrypted, got %q legacy=%v err=%v", *saved.AccessToken, legacy, err)
	}
	if !saved.ExpiresAt.Equal(testNow.Add(3599*time.Second)) || saved.RefreshToken != nil {
		t.Errorf("expiry %v, refresh %v", saved.ExpiresAt, saved.RefreshToken)
	}
}

func TestATokenWithoutAnExpiryIsRefreshed(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.ExpiresAt = nil

	f.mustRun(t)

	if len(f.google.refreshes) != 1 {
		t.Error("want a refresh")
	}
}

func TestLegacyPlainTextTokensAreReadOnceAndReEncrypted(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.AccessToken, f.conns.conn.RefreshToken = "legacy-access", "legacy-refresh"

	f.mustRun(t)

	if f.google.authorization[0] != "Bearer legacy-access" {
		t.Errorf("the legacy token is still usable, got %q", f.google.authorization[0])
	}
	for field, stored := range map[string]string{"access_token": f.conns.conn.AccessToken, "refresh_token": f.conns.conn.RefreshToken} {
		plain, legacy, err := f.cipher.Decrypt(stored, tokencrypt.ConnectionContext(testConn, field))
		if err != nil || legacy || plain != "legacy-"+strings.TrimSuffix(field, "_token") {
			t.Errorf("%s must now be stored encrypted, got %q", field, stored)
		}
	}
}

func TestALegacyRefreshTokenIsReEncryptedWhenRefreshing(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.RefreshToken, f.conns.conn.ExpiresAt = "legacy-refresh", nil

	f.mustRun(t)

	if f.google.refreshes[0] != "legacy-refresh" || !strings.HasPrefix(f.conns.conn.RefreshToken, "v2:") || !strings.HasPrefix(f.conns.conn.AccessToken, "v2:") {
		t.Errorf("refresh %v, stored %q / %q", f.google.refreshes, f.conns.conn.RefreshToken, f.conns.conn.AccessToken)
	}
}

func TestATokenSealedWithAnotherKeyFailsClearly(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.AccessToken = seal(t, testCipher(t), "x", testConn, "access_token")

	_, err := f.run(t)

	if !errors.Is(err, errTokenUnreadable) || f.conns.failures[0].Reason != "stored gmail token can't be decrypted" {
		t.Fatalf("got %v / %+v", err, f.conns.failures)
	}
	if len(f.google.requests) != 0 {
		t.Error("nothing may be sent to Google with an unreadable token")
	}
}

func TestATokenCopiedFromAnotherConnectionIsRejected(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.RefreshToken = seal(t, f.cipher, "theirs", "00000000-0000-4000-8000-000000000000", "refresh_token")

	if _, err := f.run(t); !errors.Is(err, errTokenUnreadable) {
		t.Fatalf("got %v", err)
	}
}

func TestARevokedRefreshTokenPutsTheConnectionInError(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.ExpiresAt = nil
	f.google.tokenStatus, f.google.tokenBody = 400, `{"error":"invalid_grant"}`

	_, err := f.run(t)

	if !errors.Is(err, errAccessRevoked) || f.conns.conn.Status != "error" || f.conns.revoked == "" {
		t.Fatalf("want revoked + error state, got %v status=%s", err, f.conns.conn.Status)
	}
	if f.conns.failures[0].Reason != "gmail access revoked: reconnect Gmail" {
		t.Errorf("reason %q", f.conns.failures[0].Reason)
	}
}

func TestAFailedTokenRefreshFailsTheJob(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.ExpiresAt = nil
	f.google.tokenStatus = 503

	if _, err := f.run(t); err == nil || errors.Is(err, errAccessRevoked) {
		t.Fatalf("got %v", err)
	}
}

func TestNoRefreshTokenMeansReconnect(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.RefreshToken, f.conns.conn.ExpiresAt = "", nil

	if _, err := f.run(t); !errors.Is(err, errNoTokens) || f.conns.failures[0].Reason != "gmail is not connected" {
		t.Fatalf("got %v", err)
	}
}

func TestAStoreWriteFailureDoesNotFailASyncThatHasAToken(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.ExpiresAt = nil
	f.conns.saveErr = errors.New("db down")

	if _, err := f.run(t); err != nil {
		t.Fatalf("the refreshed token still works: %v", err)
	}
}

// --- Connection state and payload ---

func TestADisconnectedOrMissingConnectionIsNotSynced(t *testing.T) {
	f := newSyncFixture(t, "")
	f.conns.conn.Status = "disconnected"
	if _, err := f.run(t); !errors.Is(err, errNotConnected) {
		t.Errorf("disconnected: %v", err)
	}
	f.conns.conn = nil
	if _, err := f.run(t); !errors.Is(err, errConnectionNotFound) {
		t.Errorf("missing: %v", err)
	}
}

func TestParseRequiresUUIDs(t *testing.T) {
	h := NewGmailHandler(nil, nil, GmailConfig{})
	ok := `{"user_id":"` + testUser + `","connection_id":"` + testConn + `","sync_session_id":"` + testSession + `"}`
	if _, err := h.Parse(json.RawMessage(ok)); err != nil {
		t.Fatalf("valid payload: %v", err)
	}
	for _, bad := range []string{
		`{"connection_id":"` + testConn + `","sync_session_id":"` + testSession + `"}`,
		`{"user_id":"` + testUser + `","connection_id":"nope","sync_session_id":"` + testSession + `"}`,
		`{"user_id":"` + testUser + `","connection_id":"` + testConn + `"}`,
		`not json`,
	} {
		if _, err := h.Parse(json.RawMessage(bad)); err == nil {
			t.Errorf("want an error for %s", bad)
		}
	}
}

func TestExecuteRejectsAnUnexpectedPayload(t *testing.T) {
	if _, err := newSyncFixture(t, "").h.Execute(context.Background(), "nope"); err == nil {
		t.Fatal("want an error")
	}
}

func TestACancelledSyncIsRecordedAsInterrupted(t *testing.T) {
	f := newSyncFixture(t, "")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := f.h.Execute(ctx, &GmailPayload{UserID: testUser, ConnectionID: testConn, SyncSessionID: testSession})

	if err == nil || f.conns.failures[0].Reason != "gmail sync interrupted" {
		t.Fatalf("got %v / %+v", err, f.conns.failures)
	}
}
