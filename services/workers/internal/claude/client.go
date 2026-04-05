package claude

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Client is an HTTP client for the Anthropic Claude API.
type Client struct {
	httpClient *http.Client
	apiKey     string
	model      string
	baseURL    string
}

// NewClient creates a Claude API client.
func NewClient(apiKey, model string) *Client {
	if model == "" {
		model = "claude-sonnet-4-20250514"
	}
	return &Client{
		httpClient: &http.Client{Timeout: 30 * time.Second},
		apiKey:     apiKey,
		model:      model,
		baseURL:    "https://api.anthropic.com/v1",
	}
}

// Model returns the configured model name.
func (c *Client) Model() string { return c.model }

type request struct {
	Model     string    `json:"model"`
	MaxTokens int       `json:"max_tokens"`
	Messages  []message `json:"messages"`
	System    string    `json:"system,omitempty"`
}

type message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type response struct {
	Content []struct {
		Text string `json:"text"`
	} `json:"content"`
	Usage struct {
		InputTokens  int `json:"input_tokens"`
		OutputTokens int `json:"output_tokens"`
	} `json:"usage"`
}

// Result holds the Claude API response data.
type Result struct {
	Text         string
	InputTokens  int
	OutputTokens int
	Model        string
}

// Complete sends a prompt to Claude and returns the response.
func (c *Client) Complete(ctx context.Context, system, userPrompt string, maxTokens int) (*Result, error) {
	req := request{
		Model:     c.model,
		MaxTokens: maxTokens,
		System:    system,
		Messages:  []message{{Role: "user", Content: userPrompt}},
	}

	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/messages", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("x-api-key", c.apiKey)
	httpReq.Header.Set("anthropic-version", "2023-06-01")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("claude request failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(resp.Body)

	if resp.StatusCode == 429 {
		return nil, &RetryableError{Message: "Claude rate limited", StatusCode: 429}
	}
	if resp.StatusCode == 400 || resp.StatusCode == 401 {
		return nil, &PermanentError{Message: fmt.Sprintf("Claude error %d: %s", resp.StatusCode, string(respBody))}
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("claude unexpected status %d: %s", resp.StatusCode, string(respBody))
	}

	var apiResp response
	if err := json.Unmarshal(respBody, &apiResp); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}

	if len(apiResp.Content) == 0 {
		return nil, fmt.Errorf("claude returned empty content")
	}

	return &Result{
		Text:         apiResp.Content[0].Text,
		InputTokens:  apiResp.Usage.InputTokens,
		OutputTokens: apiResp.Usage.OutputTokens,
		Model:        c.model,
	}, nil
}

// RetryableError indicates the request can be retried (e.g., 429 rate limit).
type RetryableError struct {
	Message    string
	StatusCode int
}

func (e *RetryableError) Error() string { return e.Message }

// PermanentError indicates the request should not be retried (e.g., 400/401).
type PermanentError struct {
	Message string
}

func (e *PermanentError) Error() string { return e.Message }
