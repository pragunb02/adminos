package gmail

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Client is an HTTP client for the Gmail API and Google's token endpoint.
//
// Temporary failures (429, 5xx, network errors) are retried with exponential backoff
// (ENGINEERING.md §6); 401/403/404 are not. Error messages carry the status only, never response
// bodies, tokens or email content.
type Client struct {
	httpClient   *http.Client
	gmailBaseURL string
	tokenURL     string
	retry        RetryPolicy
	sleep        func(ctx context.Context, d time.Duration) error
}

// RetryPolicy is how often and how patiently temporary failures are retried.
type RetryPolicy struct {
	Attempts  int           // total tries, at least 1
	BaseDelay time.Duration // delay before the 2nd try; doubled each time
	MaxDelay  time.Duration
}

// DefaultRetry: 4 tries, 500 ms → 1 s → 2 s.
var DefaultRetry = RetryPolicy{Attempts: 4, BaseDelay: 500 * time.Millisecond, MaxDelay: 8 * time.Second}

// NewClient creates a Gmail API client with production endpoints.
func NewClient() *Client {
	return &Client{
		httpClient:   &http.Client{Timeout: 30 * time.Second},
		gmailBaseURL: "https://gmail.googleapis.com",
		tokenURL:     "https://oauth2.googleapis.com/token",
		retry:        DefaultRetry,
		sleep:        sleepCtx,
	}
}

// NewClientWithEndpoints creates a client for other Gmail/token endpoints (e.g. a local test
// server) and retry policy.
func NewClientWithEndpoints(gmailBaseURL, tokenURL string, retry RetryPolicy) *Client {
	c := NewClient()
	c.gmailBaseURL, c.tokenURL, c.retry = gmailBaseURL, tokenURL, retry
	return c
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// --- Gmail API models (this package only; callers get domain-shaped values) ---

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
	MimeType string          `json:"mimeType"`
	Filename string          `json:"filename,omitempty"`
	Headers  []MessageHeader `json:"headers"`
	Body     MessagePartBody `json:"body"`
	Parts    []MessagePart   `json:"parts,omitempty"`
}

// MessageHeader is a single header key-value pair.
type MessageHeader struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

// MessagePartBody holds the body data of a message part (base64url).
type MessagePartBody struct {
	AttachmentID string `json:"attachmentId,omitempty"`
	Size         int    `json:"size"`
	Data         string `json:"data"`
}

// Message is the Gmail message resource.
type Message struct {
	ID        string      `json:"id"`
	ThreadID  string      `json:"threadId"`
	LabelIDs  []string    `json:"labelIds"`
	Snippet   string      `json:"snippet"`
	HistoryID string      `json:"historyId"`
	Payload   MessagePart `json:"payload"`
}

// Header returns the first header with this name (case-insensitive), or "".
func (m *Message) Header(name string) string {
	for _, h := range m.Payload.Headers {
		if strings.EqualFold(h.Name, name) {
			return h.Value
		}
	}
	return ""
}

// Profile is the part of users.getProfile we use.
type Profile struct {
	EmailAddress string `json:"emailAddress"`
	HistoryID    string `json:"historyId"`
}

// HistoryListResponse is the response from users.history.list.
type HistoryListResponse struct {
	History []struct {
		MessagesAdded []struct {
			Message MessageListEntry `json:"message"`
		} `json:"messagesAdded"`
	} `json:"history"`
	NextPageToken string `json:"nextPageToken,omitempty"`
	HistoryID     string `json:"historyId"`
}

// TokenResponse is the response from the OAuth2 token endpoint.
type TokenResponse struct {
	AccessToken string `json:"access_token"`
	ExpiresIn   int    `json:"expires_in"`
	TokenType   string `json:"token_type"`
	Scope       string `json:"scope"`
}

// --- Calls ---

// GetProfile returns the mailbox's current history ID (the starting point of the next incremental sync).
func (c *Client) GetProfile(ctx context.Context, accessToken string) (*Profile, error) {
	var p Profile
	if err := c.getJSON(ctx, accessToken, "/gmail/v1/users/me/profile", nil, &p); err != nil {
		return nil, fmt.Errorf("get profile: %w", err)
	}
	return &p, nil
}

// ListMessages fetches a page of message IDs matching the Gmail search query.
func (c *Client) ListMessages(ctx context.Context, accessToken, query, pageToken string) (*MessageListResponse, error) {
	q := url.Values{"q": {query}, "maxResults": {"100"}}
	if pageToken != "" {
		q.Set("pageToken", pageToken)
	}
	var result MessageListResponse
	if err := c.getJSON(ctx, accessToken, "/gmail/v1/users/me/messages", q, &result); err != nil {
		return nil, fmt.Errorf("list messages: %w", err)
	}
	return &result, nil
}

// ListHistory fetches a page of mailbox changes (messages added) since startHistoryID.
// Gmail answers 404 when startHistoryID is too old: that is ErrHistoryExpired.
func (c *Client) ListHistory(ctx context.Context, accessToken, startHistoryID, pageToken string) (*HistoryListResponse, error) {
	q := url.Values{"startHistoryId": {startHistoryID}, "historyTypes": {"messageAdded"}, "maxResults": {"500"}}
	if pageToken != "" {
		q.Set("pageToken", pageToken)
	}
	var result HistoryListResponse
	err := c.getJSON(ctx, accessToken, "/gmail/v1/users/me/history", q, &result)
	if errors.Is(err, ErrNotFound) {
		return nil, ErrHistoryExpired
	}
	if err != nil {
		return nil, fmt.Errorf("list history: %w", err)
	}
	return &result, nil
}

// GetMessageMetadata fetches only a message's From, Subject and Authentication-Results headers (no body;
// the subject only tells a receipt sender's order mail from its promotions, AOS-903), so a message can be
// qualified (Qualify) before anything else of it is read.
func (c *Client) GetMessageMetadata(ctx context.Context, accessToken, messageID string) (*Message, error) {
	q := url.Values{"format": {"metadata"}, "metadataHeaders": {"From", "Subject", AuthResultsHeader}}
	var msg Message
	if err := c.getJSON(ctx, accessToken, "/gmail/v1/users/me/messages/"+url.PathEscape(messageID), q, &msg); err != nil {
		return nil, fmt.Errorf("get message metadata: %w", err)
	}
	return &msg, nil
}

// GetMessage fetches a single message with its full MIME body.
func (c *Client) GetMessage(ctx context.Context, accessToken, messageID string) (*Message, error) {
	q := url.Values{"format": {"full"}}
	var msg Message
	if err := c.getJSON(ctx, accessToken, "/gmail/v1/users/me/messages/"+url.PathEscape(messageID), q, &msg); err != nil {
		return nil, fmt.Errorf("get message: %w", err)
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
	}.Encode()

	var tokenResp TokenResponse
	err := c.withRetry(ctx, func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.tokenURL, strings.NewReader(form))
		if err != nil {
			return &PermanentError{Message: "create refresh request"}
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		resp, err := c.httpClient.Do(req)
		if err != nil {
			return &RetryableError{Message: "token endpoint unreachable"}
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		if err != nil {
			return &RetryableError{Message: "read token response"}
		}
		switch {
		case resp.StatusCode == http.StatusOK:
			if err := json.Unmarshal(body, &tokenResp); err != nil || tokenResp.AccessToken == "" {
				return &PermanentError{Message: "token response has no access token"}
			}
			return nil
		case resp.StatusCode == http.StatusBadRequest || resp.StatusCode == http.StatusUnauthorized:
			var errResp struct {
				Error string `json:"error"`
			}
			if json.Unmarshal(body, &errResp) == nil && errResp.Error == "invalid_grant" {
				return &TokenRevokedError{Message: "refresh token revoked or expired"}
			}
			return &PermanentError{Message: fmt.Sprintf("token refresh refused (%d)", resp.StatusCode)}
		case resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500:
			return &RetryableError{Message: fmt.Sprintf("token refresh temporary error (%d)", resp.StatusCode), StatusCode: resp.StatusCode}
		default:
			return &PermanentError{Message: fmt.Sprintf("token refresh unexpected status %d", resp.StatusCode)}
		}
	})
	if err != nil {
		return nil, err
	}
	return &tokenResp, nil
}

// getJSON GETs path?query from the Gmail API, with retries, and decodes the JSON answer into out.
func (c *Client) getJSON(ctx context.Context, accessToken, path string, query url.Values, out any) error {
	u := c.gmailBaseURL + path
	if len(query) > 0 {
		u += "?" + query.Encode()
	}
	return c.withRetry(ctx, func() error {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
		if err != nil {
			return &PermanentError{Message: "create gmail request"}
		}
		req.Header.Set("Authorization", "Bearer "+accessToken)
		resp, err := c.httpClient.Do(req)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return &RetryableError{Message: "gmail unreachable"}
		}
		defer resp.Body.Close()
		if err := checkResponse(resp); err != nil {
			return err
		}
		if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
			return &PermanentError{Message: "decode gmail response"}
		}
		return nil
	})
}

// withRetry runs call until it succeeds, fails permanently, or the attempts run out.
func (c *Client) withRetry(ctx context.Context, call func() error) error {
	attempts := c.retry.Attempts
	if attempts < 1 {
		attempts = 1
	}
	delay := c.retry.BaseDelay
	var err error
	for i := 0; i < attempts; i++ {
		if i > 0 {
			if serr := c.sleep(ctx, delay); serr != nil {
				return serr
			}
			delay *= 2
			if c.retry.MaxDelay > 0 && delay > c.retry.MaxDelay {
				delay = c.retry.MaxDelay
			}
		}
		err = call()
		var retryable *RetryableError
		if err == nil || !errors.As(err, &retryable) {
			return err
		}
	}
	return err
}

// checkResponse maps a non-200 Gmail answer to a typed error. The body is drained, never quoted.
func checkResponse(resp *http.Response) error {
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	switch {
	case resp.StatusCode == http.StatusUnauthorized:
		return &PermanentError{Message: "gmail auth error (401)"}
	case resp.StatusCode == http.StatusForbidden:
		return &PermanentError{Message: "gmail forbidden (403)"}
	case resp.StatusCode == http.StatusNotFound:
		return ErrNotFound
	case resp.StatusCode == http.StatusTooManyRequests:
		return &RetryableError{Message: "gmail rate limited (429)", StatusCode: resp.StatusCode}
	case resp.StatusCode >= 500:
		return &RetryableError{Message: fmt.Sprintf("gmail server error (%d)", resp.StatusCode), StatusCode: resp.StatusCode}
	default:
		return &PermanentError{Message: fmt.Sprintf("gmail unexpected status %d", resp.StatusCode)}
	}
}

// ErrNotFound is Gmail's 404: the message (or history start) no longer exists.
var ErrNotFound = errors.New("gmail: not found (404)")

// ErrHistoryExpired means the stored history ID is too old for history.list: do a full sync instead.
var ErrHistoryExpired = errors.New("gmail: history id expired")

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
