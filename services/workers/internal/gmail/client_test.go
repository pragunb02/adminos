package gmail

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// newTestClient creates a Client pointing at the given mock server URL.
func newTestClient(gmailURL, tokenURL string) *Client {
	return &Client{
		httpClient:   &http.Client{Timeout: 5 * time.Second},
		gmailBaseURL: gmailURL,
		tokenURL:     tokenURL,
	}
}

func TestListMessages_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("unexpected auth header: %s", r.Header.Get("Authorization"))
		}

		resp := MessageListResponse{
			Messages: []MessageListEntry{
				{ID: "msg1", ThreadID: "thread1"},
				{ID: "msg2", ThreadID: "thread2"},
			},
			NextPageToken:      "next123",
			ResultSizeEstimate: 2,
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	client := newTestClient(server.URL, "")
	result, err := client.ListMessages(context.Background(), "test-token", "from:bank", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(result.Messages) != 2 {
		t.Fatalf("expected 2 messages, got %d", len(result.Messages))
	}
	if result.Messages[0].ID != "msg1" {
		t.Errorf("expected first message ID 'msg1', got %q", result.Messages[0].ID)
	}
	if result.NextPageToken != "next123" {
		t.Errorf("expected NextPageToken 'next123', got %q", result.NextPageToken)
	}
}

func TestGetMessage_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		msg := Message{
			ID:        "msg123",
			ThreadID:  "thread456",
			LabelIDs:  []string{"INBOX"},
			Snippet:   "Your payment of Rs 500 was successful",
			HistoryID: "12345",
			Payload: MessagePart{
				MimeType: "text/plain",
				Headers: []MessageHeader{
					{Name: "Subject", Value: "Payment Confirmation"},
					{Name: "From", Value: "bank@example.com"},
				},
			},
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(msg)
	}))
	defer server.Close()

	client := newTestClient(server.URL, "")
	msg, err := client.GetMessage(context.Background(), "test-token", "msg123")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if msg.ID != "msg123" {
		t.Errorf("expected ID 'msg123', got %q", msg.ID)
	}
	if msg.Snippet != "Your payment of Rs 500 was successful" {
		t.Errorf("unexpected snippet: %q", msg.Snippet)
	}
	if len(msg.Payload.Headers) != 2 {
		t.Fatalf("expected 2 headers, got %d", len(msg.Payload.Headers))
	}
	if msg.Payload.Headers[0].Value != "Payment Confirmation" {
		t.Errorf("expected Subject header 'Payment Confirmation', got %q", msg.Payload.Headers[0].Value)
	}
}

func TestRefreshToken_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if err := r.ParseForm(); err != nil {
			t.Fatalf("failed to parse form: %v", err)
		}
		if r.FormValue("grant_type") != "refresh_token" {
			t.Errorf("unexpected grant_type: %s", r.FormValue("grant_type"))
		}
		if r.FormValue("client_id") != "test-client-id" {
			t.Errorf("unexpected client_id: %s", r.FormValue("client_id"))
		}

		resp := TokenResponse{
			AccessToken: "new-access-token",
			ExpiresIn:   3600,
			TokenType:   "Bearer",
			Scope:       "https://www.googleapis.com/auth/gmail.readonly",
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	client := newTestClient("", server.URL)
	token, err := client.RefreshToken(context.Background(), "test-client-id", "test-secret", "refresh-tok")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if token.AccessToken != "new-access-token" {
		t.Errorf("expected 'new-access-token', got %q", token.AccessToken)
	}
	if token.ExpiresIn != 3600 {
		t.Errorf("expected ExpiresIn=3600, got %d", token.ExpiresIn)
	}
}

func TestRefreshToken_Revoked(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{
			"error":             "invalid_grant",
			"error_description": "Token has been revoked",
		})
	}))
	defer server.Close()

	client := newTestClient("", server.URL)
	_, err := client.RefreshToken(context.Background(), "cid", "csecret", "revoked-token")
	if err == nil {
		t.Fatal("expected error for revoked token")
	}

	_, ok := err.(*TokenRevokedError)
	if !ok {
		t.Fatalf("expected *TokenRevokedError, got %T: %v", err, err)
	}
}

func TestListMessages_RateLimit429(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		w.Write([]byte(`{"error":"rate_limited"}`))
	}))
	defer server.Close()

	client := newTestClient(server.URL, "")
	_, err := client.ListMessages(context.Background(), "test-token", "q", "")
	if err == nil {
		t.Fatal("expected error for 429 response")
	}

	retryErr, ok := err.(*RetryableError)
	if !ok {
		t.Fatalf("expected *RetryableError, got %T: %v", err, err)
	}
	if retryErr.StatusCode != 429 {
		t.Errorf("expected StatusCode=429, got %d", retryErr.StatusCode)
	}
}

func TestGetMessage_AuthError401(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
	}))
	defer server.Close()

	client := newTestClient(server.URL, "")
	_, err := client.GetMessage(context.Background(), "bad-token", "msg1")
	if err == nil {
		t.Fatal("expected error for 401 response")
	}

	_, ok := err.(*PermanentError)
	if !ok {
		t.Fatalf("expected *PermanentError, got %T: %v", err, err)
	}
}
