package gmail

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// retryingClient retries without real waiting and records the delays it would have slept.
func retryingClient(gmailURL, tokenURL string, attempts int) (*Client, *[]time.Duration) {
	var slept []time.Duration
	c := newTestClient(gmailURL, tokenURL)
	c.retry = RetryPolicy{Attempts: attempts, BaseDelay: 100 * time.Millisecond, MaxDelay: 250 * time.Millisecond}
	c.sleep = func(ctx context.Context, d time.Duration) error {
		slept = append(slept, d)
		return ctx.Err()
	}
	return c, &slept
}

func TestTemporaryGmailErrorsAreRetriedWithBackoff(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) < 4 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.Write([]byte(`{"emailAddress":"owner@example.com","historyId":"98765"}`))
	}))
	defer server.Close()
	c, slept := retryingClient(server.URL, "", 4)

	p, err := c.GetProfile(context.Background(), "tok")

	if err != nil || p.HistoryID != "98765" {
		t.Fatalf("want history 98765 after retries, got %+v, %v", p, err)
	}
	if calls.Load() != 4 {
		t.Errorf("want 4 calls, got %d", calls.Load())
	}
	want := []time.Duration{100 * time.Millisecond, 200 * time.Millisecond, 250 * time.Millisecond}
	if len(*slept) != len(want) {
		t.Fatalf("want delays %v, got %v", want, *slept)
	}
	for i := range want {
		if (*slept)[i] != want[i] {
			t.Errorf("delay %d: want %v, got %v", i, want[i], (*slept)[i])
		}
	}
}

func TestRetriesStopAfterTheLastAttempt(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer server.Close()
	c, _ := retryingClient(server.URL, "", 3)

	_, err := c.ListMessages(context.Background(), "tok", "q", "")

	var retryable *RetryableError
	if !errors.As(err, &retryable) || calls.Load() != 3 {
		t.Fatalf("want a RetryableError after 3 calls, got %v after %d", err, calls.Load())
	}
}

func TestAuthErrorsAreNotRetried(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()
	c, _ := retryingClient(server.URL, "", 4)

	_, err := c.GetMessage(context.Background(), "tok", "m1")

	var permanent *PermanentError
	if !errors.As(err, &permanent) || calls.Load() != 1 {
		t.Fatalf("want one call and a PermanentError, got %v after %d", err, calls.Load())
	}
}

func TestACancelledContextStopsRetrying(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer server.Close()
	c := newTestClient(server.URL, "")
	c.retry = RetryPolicy{Attempts: 5, BaseDelay: time.Hour}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := c.GetProfile(ctx, "tok")

	if !errors.Is(err, context.Canceled) {
		t.Fatalf("want context.Canceled, got %v", err)
	}
}

func TestHistoryListAsksForAddedMessagesSinceTheStartID(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if r.URL.Path != "/gmail/v1/users/me/history" || q.Get("startHistoryId") != "1000" ||
			q.Get("historyTypes") != "messageAdded" || q.Get("pageToken") != "p2" {
			t.Errorf("unexpected request %s", r.URL)
		}
		w.Write([]byte(`{"history":[{"messagesAdded":[{"message":{"id":"m1","threadId":"t1"}}]}],"historyId":"1042"}`))
	}))
	defer server.Close()

	page, err := newTestClient(server.URL, "").ListHistory(context.Background(), "tok", "1000", "p2")

	if err != nil || page.HistoryID != "1042" || page.History[0].MessagesAdded[0].Message.ID != "m1" {
		t.Fatalf("unexpected page %+v, %v", page, err)
	}
}

func TestAnExpiredHistoryIDIsErrHistoryExpired(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	_, err := newTestClient(server.URL, "").ListHistory(context.Background(), "tok", "5", "")

	if !errors.Is(err, ErrHistoryExpired) {
		t.Fatalf("want ErrHistoryExpired, got %v", err)
	}
}

func TestAMissingMessageIsErrNotFound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	_, err := newTestClient(server.URL, "").GetMessage(context.Background(), "tok", "gone")

	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestMetadataRequestsOnlyFromAndSubject(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if q.Get("format") != "metadata" || strings.Join(q["metadataHeaders"], ",") != "From,Subject" {
			t.Errorf("unexpected query %s", r.URL.RawQuery)
		}
		w.Write([]byte(`{"id":"m1","payload":{"headers":[{"name":"from","value":"HDFC Bank <alerts@hdfcbank.net>"}]}}`))
	}))
	defer server.Close()

	msg, err := newTestClient(server.URL, "").GetMessageMetadata(context.Background(), "tok", "m1")

	if err != nil || msg.Header("From") != "HDFC Bank <alerts@hdfcbank.net>" || msg.Header("Subject") != "" {
		t.Fatalf("unexpected %+v, %v", msg, err)
	}
}

func TestListMessagesSendsTheQueryAndPageToken(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("q") != "from:(hdfcbank.net)" || r.URL.Query().Get("pageToken") != "next" {
			t.Errorf("unexpected query %s", r.URL.RawQuery)
		}
		w.Write([]byte(`{"messages":[]}`))
	}))
	defer server.Close()

	if _, err := newTestClient(server.URL, "").ListMessages(context.Background(), "tok", "from:(hdfcbank.net)", "next"); err != nil {
		t.Fatal(err)
	}
}

func TestErrorsNeverQuoteTheResponseBody(t *testing.T) {
	const body = `{"error":{"message":"Rs 500 debited from a/c XX1234"}}`
	for _, status := range []int{400, 401, 403, 418, 500} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(status)
			w.Write([]byte(body))
		}))
		_, err := newTestClient(server.URL, server.URL).GetMessage(context.Background(), "tok", "m1")
		_, refreshErr := newTestClient(server.URL, server.URL).RefreshToken(context.Background(), "id", "secret", "refresh")
		server.Close()
		for _, e := range []error{err, refreshErr} {
			if e == nil || strings.Contains(e.Error(), "XX1234") || strings.Contains(e.Error(), "refresh\"") {
				t.Errorf("status %d: error must not quote the body: %v", status, e)
			}
		}
	}
}

func TestATemporaryTokenEndpointErrorIsRetried(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.Write([]byte(`{"access_token":"fresh","expires_in":3599}`))
	}))
	defer server.Close()
	c, _ := retryingClient("", server.URL, 3)

	tok, err := c.RefreshToken(context.Background(), "id", "secret", "refresh")

	if err != nil || tok.AccessToken != "fresh" || calls.Load() != 2 {
		t.Fatalf("want a token on the 2nd call, got %+v, %v after %d", tok, err, calls.Load())
	}
}

func TestATokenResponseWithoutAnAccessTokenIsAnError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"expires_in":3599}`))
	}))
	defer server.Close()

	if _, err := newTestClient("", server.URL).RefreshToken(context.Background(), "id", "s", "r"); err == nil {
		t.Fatal("want an error")
	}
}

func TestAnUnreachableGmailIsRetryable(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := server.URL
	server.Close()

	_, err := newTestClient(url, url).GetProfile(context.Background(), "tok")
	_, refreshErr := newTestClient(url, url).RefreshToken(context.Background(), "id", "s", "r")

	var retryable *RetryableError
	if !errors.As(err, &retryable) || !errors.As(refreshErr, &retryable) {
		t.Fatalf("want RetryableErrors, got %v / %v", err, refreshErr)
	}
}

func TestNewClientWithEndpointsUsesThem(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"historyId":"7"}`))
	}))
	defer server.Close()

	p, err := NewClientWithEndpoints(server.URL, server.URL, DefaultRetry).GetProfile(context.Background(), "tok")

	if err != nil || p.HistoryID != "7" {
		t.Fatalf("got %+v, %v", p, err)
	}
}
