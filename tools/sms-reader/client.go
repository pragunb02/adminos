package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

type rawSMS struct {
	Sender     string `json:"sender"`
	Text       string `json:"text"`
	ReceivedAt string `json:"receivedAt"`
}

type batchResult struct {
	Accepted   int `json:"accepted"`
	Duplicates int `json:"duplicates"`
	Ignored    int `json:"ignored"`
}

// postBatch sends messages to the AdminOS API. Errors never include message text.
func postBatch(ctx context.Context, hc *http.Client, apiURL, token string, msgs []SMS) (batchResult, error) {
	payload := struct {
		Messages []rawSMS `json:"messages"`
	}{}
	for _, m := range msgs {
		payload.Messages = append(payload.Messages, rawSMS{m.Sender, m.Text, m.ReceivedAt.Format(time.RFC3339)})
	}
	body, _ := json.Marshal(payload)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, apiURL+"/api/v1/ingest/sms/raw", bytes.NewReader(body))
	if err != nil {
		return batchResult{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)

	resp, err := hc.Do(req)
	if err != nil {
		return batchResult{}, fmt.Errorf("post to API: %w", err)
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode == http.StatusUnauthorized {
		return batchResult{}, errUnauthorized
	}
	if resp.StatusCode != http.StatusAccepted {
		return batchResult{}, fmt.Errorf("API returned %d", resp.StatusCode)
	}
	var env struct {
		Data batchResult `json:"data"`
	}
	if err := json.Unmarshal(respBody, &env); err != nil {
		return batchResult{}, fmt.Errorf("decode API response: %w", err)
	}
	return env.Data, nil
}

var errUnauthorized = fmt.Errorf("API rejected the device token (revoked or expired?): create a new one in Settings")
