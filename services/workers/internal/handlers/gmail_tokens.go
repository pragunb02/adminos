package handlers

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/adminos/adminos/workers/internal/gmail"
	"github.com/adminos/adminos/workers/internal/tokencrypt"
)

// tokenRefreshMargin: an access token this close to expiry is refreshed first.
const tokenRefreshMargin = 5 * time.Minute

var (
	errNoTokens           = errors.New("gmail connection has no usable tokens: reconnect Gmail")
	errNotConnected       = errors.New("gmail connection is not connected")
	errTokenUnreadable    = errors.New("stored gmail token can't be decrypted (is TOKEN_ENCRYPTION_KEY the API's key?)")
	errAccessRevoked      = errors.New("gmail access revoked: reconnect Gmail")
	errGmailNotConfigured = errors.New("gmail ingestion needs a database, a Gmail client and TOKEN_ENCRYPTION_KEY")
)

// accessToken returns a usable access token for the connection (AOS-401, D-59):
//   - tokens are decrypted with their row/field context; a "v2:" value that fails to decrypt is an error;
//   - legacy plain-text tokens are read once and stored encrypted;
//   - an access token within tokenRefreshMargin of expiry (or with no expiry) is refreshed and the
//     new one stored encrypted; a revoked refresh token puts the connection in the error state.
//
// Tokens are never logged or put in errors.
func (h *GmailHandler) accessToken(ctx context.Context, connectionID string, conn *storedConnection) (string, error) {
	accessCtx := tokencrypt.ConnectionContext(connectionID, "access_token")
	refreshCtx := tokencrypt.ConnectionContext(connectionID, "refresh_token")

	access, accessLegacy, err := h.open(conn.AccessToken, accessCtx)
	if err != nil {
		return "", err
	}
	refresh, refreshLegacy, err := h.open(conn.RefreshToken, refreshCtx)
	if err != nil {
		return "", err
	}

	var update tokenUpdate
	if refreshLegacy {
		sealed, err := h.cipher.Encrypt(refresh, refreshCtx)
		if err != nil {
			return "", fmt.Errorf("encrypt refresh token: %w", err)
		}
		update.RefreshToken = &sealed
	}

	fresh := access != "" && conn.ExpiresAt != nil && h.now().Before(conn.ExpiresAt.Add(-tokenRefreshMargin))
	if fresh {
		if accessLegacy {
			sealed, err := h.cipher.Encrypt(access, accessCtx)
			if err != nil {
				return "", fmt.Errorf("encrypt access token: %w", err)
			}
			update.AccessToken = &sealed
		}
		h.saveTokens(ctx, connectionID, update)
		return access, nil
	}

	if refresh == "" {
		return "", errNoTokens
	}
	resp, err := h.gmailClient.RefreshToken(ctx, h.clientID, h.clientSecret, refresh)
	if err != nil {
		var revoked *gmail.TokenRevokedError
		if errors.As(err, &revoked) {
			if markErr := h.conns.MarkRevoked(ctx, connectionID, errAccessRevoked.Error()); markErr != nil {
				log.Printf("[gmail] Could not mark connection %s revoked: %v", connectionID, markErr)
			}
			return "", fmt.Errorf("%w: %w", errAccessRevoked, err)
		}
		return "", fmt.Errorf("refresh access token: %w", err)
	}
	sealed, err := h.cipher.Encrypt(resp.AccessToken, accessCtx)
	if err != nil {
		return "", fmt.Errorf("encrypt access token: %w", err)
	}
	expiry := h.now().Add(time.Duration(resp.ExpiresIn) * time.Second)
	update.AccessToken, update.ExpiresAt = &sealed, &expiry
	h.saveTokens(ctx, connectionID, update)
	return resp.AccessToken, nil
}

// open decrypts a stored token; "" stays "".
func (h *GmailHandler) open(stored, context string) (string, bool, error) {
	if stored == "" {
		return "", false, nil
	}
	plain, legacy, err := h.cipher.Decrypt(stored, context)
	if err != nil {
		return "", false, errTokenUnreadable
	}
	return plain, legacy, nil
}

// saveTokens stores re-encrypted or refreshed tokens. A failure is logged, not fatal: the token in
// hand still works, and the next run refreshes or re-encrypts again.
func (h *GmailHandler) saveTokens(ctx context.Context, connectionID string, u tokenUpdate) {
	if u.AccessToken == nil && u.RefreshToken == nil && u.ExpiresAt == nil {
		return
	}
	if err := h.conns.SaveTokens(ctx, connectionID, u); err != nil {
		log.Printf("[gmail] Could not store tokens for connection %s: %v", connectionID, err)
	}
}
