package claude

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestComplete_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/messages" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.Header.Get("x-api-key") != "test-key" {
			t.Errorf("unexpected api key: %s", r.Header.Get("x-api-key"))
		}
		if r.Header.Get("anthropic-version") != "2023-06-01" {
			t.Errorf("unexpected anthropic-version header")
		}

		resp := map[string]any{
			"content": []map[string]string{{"text": "Hello from Claude"}},
			"usage":   map[string]int{"input_tokens": 10, "output_tokens": 20},
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	client := NewClient("test-key", "test-model")
	client.baseURL = server.URL

	result, err := client.Complete(context.Background(), "system prompt", "hello", 100)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.Text != "Hello from Claude" {
		t.Errorf("expected 'Hello from Claude', got %q", result.Text)
	}
	if result.InputTokens != 10 {
		t.Errorf("expected InputTokens=10, got %d", result.InputTokens)
	}
	if result.OutputTokens != 20 {
		t.Errorf("expected OutputTokens=20, got %d", result.OutputTokens)
	}
	if result.Model != "test-model" {
		t.Errorf("expected Model='test-model', got %q", result.Model)
	}
}

func TestComplete_RateLimit429(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		w.Write([]byte(`{"error":"rate_limited"}`))
	}))
	defer server.Close()

	client := NewClient("test-key", "test-model")
	client.baseURL = server.URL

	_, err := client.Complete(context.Background(), "", "hello", 100)
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

func TestComplete_BadRequest400(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"invalid_request"}`))
	}))
	defer server.Close()

	client := NewClient("test-key", "test-model")
	client.baseURL = server.URL

	_, err := client.Complete(context.Background(), "", "hello", 100)
	if err == nil {
		t.Fatal("expected error for 400 response")
	}

	permErr, ok := err.(*PermanentError)
	if !ok {
		t.Fatalf("expected *PermanentError, got %T: %v", err, err)
	}
	if permErr.Message == "" {
		t.Error("expected non-empty error message")
	}
}

func TestComplete_Unauthorized401(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"error":"unauthorized"}`))
	}))
	defer server.Close()

	client := NewClient("test-key", "test-model")
	client.baseURL = server.URL

	_, err := client.Complete(context.Background(), "", "hello", 100)
	if err == nil {
		t.Fatal("expected error for 401 response")
	}

	_, ok := err.(*PermanentError)
	if !ok {
		t.Fatalf("expected *PermanentError, got %T: %v", err, err)
	}
}

func TestComplete_TokenUsageParsing(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		resp := map[string]any{
			"content": []map[string]string{{"text": "response text"}},
			"usage":   map[string]int{"input_tokens": 1500, "output_tokens": 3200},
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	client := NewClient("test-key", "test-model")
	client.baseURL = server.URL

	result, err := client.Complete(context.Background(), "system", "user prompt", 4096)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.InputTokens != 1500 {
		t.Errorf("expected InputTokens=1500, got %d", result.InputTokens)
	}
	if result.OutputTokens != 3200 {
		t.Errorf("expected OutputTokens=3200, got %d", result.OutputTokens)
	}
}
