package gmail

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Client is an HTTP client for the Gmail API.
type Client struct {
	httpClient   *http.Client
	gmailBaseURL string
	tokenURL     string
}

// NewClient creates a Gmail API client with sensible defaults.
func NewClient() *Client {
	return &Client{
		httpClient:   &http.Client{Timeout: 30 * time.Second},
		gmailBaseURL: "https://gmail.googleapis.com",
		tokenURL:     "https://oauth2.googleapis.com/token",
	}
}

// MessageListEntry is a minimal message reference returned by ListMessages.
type MessageListEntry struct {
	ID       string `json:"id"`
	ThreadID string `json:"threadId"`
}

// MessageListResponse is the response from Gmail messages.list.
type MessageListResponse struct {
	Messages           []MessageListEntry `json:"messages"`
	NextPageToken      string             `json:"nextPageToken,omitempty"`
	ResultSizeEstimate int                `json:"resultSizeEstimate"`
}

// MessagePart represents a MIME part of a Gmail message.
type MessagePart struct {
	MimeType string            `json:"mimeType"`
	Headers  []MessageHeader   `json:"headers"`
	Body     MessagePartBody   `json:"body"`
	Parts    []MessagePart     `json:"parts,omitempty"`
}

// MessageHeader is a single header key-value pair.
type MessageHeader struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

// MessagePartBody holds the body data of a message part.
type MessagePartBody struct {
	Size int    `json:"size"`
	Data string `json:"data"`
}

// Message is the full Gmail message resource.
type Message struct {
	ID        string      `json:"id"`
	ThreadID  string      `json:"threadId"`
	LabelIDs  []string    `json:"labelIds"`
	Snippet   string      `json:"snippet"`
	HistoryID string      `json:"historyId"`
	Payload   MessagePart `json:"payload"`
}

// TokenResponse is the response from the OAuth2 token endpoint.
type TokenResponse struct {
	AccessToken string `json:"access_token"`
	ExpiresIn   int    `json:"expires_in"`
	TokenType   string `json:"token_type"`
	Scope       string `json:"scope"`
}

// ListMessages fetches a page of message IDs matching the given query.
func (c *Client) ListMessages(ctx context.Context, accessToken, query, pageToken string) (*MessageListResponse, error) {
	u := c.gmailBaseURL + "/gmail/v1/users/me/messages?q=" + url.QueryEscape(query)
	if pageToken != "" {
		u += "&pageToken=" + url.QueryEscape(pageToken)
	}

	req, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		return nil, fmt.Errorf("create list request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("list messages request failed: %w", err)
	}
	defer resp.Body.Close()

	if err := checkResponse(resp); err != nil {
		return nil, err
	}

	var result MessageListResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode list response: %w", err)
	}
	return &result, nil
}

// GetMessage fetches a single message by ID with full format.
func (c *Client) GetMessage(ctx context.Context, accessToken, messageID string) (*Message, error) {
	u := fmt.Sprintf("%s/gmail/v1/users/me/messages/%s?format=full", c.gmailBaseURL, messageID)

	req, err := http.NewRequestWithContext(ctx, "GET", u, nil)
	if err != nil {
		return nil, fmt.Errorf("create get request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("get message request failed: %w", err)
	}
	defer resp.Body.Close()

	if err := checkResponse(resp); err != nil {
		return nil, err
	}

	var msg Message
	if err := json.NewDecoder(resp.Body).Decode(&msg); err != nil {
		return nil, fmt.Errorf("decode message response: %w", err)
	}
	return &msg, nil
}

// RefreshToken exchanges a refresh token for a new access token.
func (c *Client) RefreshToken(ctx context.Context, clientID, clientSecret, refreshToken string) (*TokenResponse, error) {
	form := url.Values{
		"grant_type":    {"refresh_token"},
		"client_id":     {clientID},
		"client_secret": {clientSecret},
		"refresh_token": {refreshToken},
	}

	req, err := http.NewRequestWithContext(ctx, "POST", c.tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, fmt.Errorf("create refresh request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("refresh token request failed: %w", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	if resp.StatusCode == 400 || resp.StatusCode == 401 {
		// Check if refresh token was revoked
		var errResp struct {
			Error            string `json:"error"`
			ErrorDescription string `json:"error_description"`
		}
		if json.Unmarshal(body, &errResp) == nil && errResp.Error == "invalid_grant" {
			return nil, &TokenRevokedError{Message: fmt.Sprintf("refresh token revoked: %s", errResp.ErrorDescription)}
		}
		return nil, &PermanentError{Message: fmt.Sprintf("token refresh failed (%d): %s", resp.StatusCode, string(body))}
	}

	if resp.StatusCode == 429 || resp.StatusCode >= 500 {
		return nil, &RetryableError{Message: fmt.Sprintf("token refresh temporary error (%d)", resp.StatusCode), StatusCode: resp.StatusCode}
	}

	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("token refresh unexpected status %d: %s", resp.StatusCode, string(body))
	}

	var tokenResp TokenResponse
	if err := json.Unmarshal(body, &tokenResp); err != nil {
		return nil, fmt.Errorf("decode token response: %w", err)
	}
	return &tokenResp, nil
}

// checkResponse returns an appropriate error for non-200 Gmail API responses.
func checkResponse(resp *http.Response) error {
	if resp.StatusCode == 200 {
		return nil
	}

	body, _ := io.ReadAll(resp.Body)

	switch {
	case resp.StatusCode == 401:
		return &PermanentError{Message: fmt.Sprintf("gmail auth error (401): %s", string(body))}
	case resp.StatusCode == 403:
		return &PermanentError{Message: fmt.Sprintf("gmail forbidden (403): %s", string(body))}
	case resp.StatusCode == 429:
		return &RetryableError{Message: "gmail rate limited", StatusCode: 429}
	case resp.StatusCode >= 500:
		return &RetryableError{Message: fmt.Sprintf("gmail server error (%d)", resp.StatusCode), StatusCode: resp.StatusCode}
	default:
		return fmt.Errorf("gmail unexpected status %d: %s", resp.StatusCode, string(body))
	}
}

// RetryableError indicates the request can be retried (e.g., 429 or 5xx).
type RetryableError struct {
	Message    string
	StatusCode int
}

func (e *RetryableError) Error() string { return e.Message }

// PermanentError indicates the request should not be retried (e.g., 401/403).
type PermanentError struct {
	Message string
}

func (e *PermanentError) Error() string { return e.Message }

// TokenRevokedError indicates the refresh token has been revoked by the user.
type TokenRevokedError struct {
	Message string
}

func (e *TokenRevokedError) Error() string { return e.Message }
