package handlers

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// storedConnection is what the Gmail sync needs from a user_connections row. Tokens are as stored:
// "v2:" ciphertext, or legacy plain text (AOS-401).
type storedConnection struct {
	Status       string
	AccessToken  string
	RefreshToken string
	ExpiresAt    *time.Time
	HistoryID    string
}

// tokenUpdate holds sealed tokens to store; nil fields are left unchanged.
type tokenUpdate struct {
	AccessToken  *string
	RefreshToken *string
	ExpiresAt    *time.Time
}

// gmailConnectionStore reads and updates the Gmail connection state the sync depends on.
// Implementations: pgConnectionStore (Postgres) and the in-memory fake in tests.
type gmailConnectionStore interface {
	Load(ctx context.Context, connectionID string) (*storedConnection, error)
	SaveTokens(ctx context.Context, connectionID string, update tokenUpdate) error
	// MarkRevoked puts the connection in the error state: Google no longer accepts its refresh token.
	MarkRevoked(ctx context.Context, connectionID, reason string) error
	// RecordFailure marks the sync session and the connection's last sync failed, with a short
	// reason that never contains tokens or email content.
	RecordFailure(ctx context.Context, f syncFailure) error
	// MarkStarted moves the sync session from queued to in_progress (AOS-630): from then on the API
	// no longer folds new pushes into it, so mail that arrives during the sync gets a sync of its own.
	MarkStarted(ctx context.Context, syncSessionID string) error
}

type syncFailure struct {
	ConnectionID  string
	SyncSessionID string
	Reason        string
}

var errConnectionNotFound = errors.New("gmail connection not found")

type pgConnectionStore struct {
	db *pgxpool.Pool
}

func (s *pgConnectionStore) Load(ctx context.Context, connectionID string) (*storedConnection, error) {
	var c storedConnection
	err := s.db.QueryRow(ctx, `
		SELECT status::text, COALESCE(access_token, ''), COALESCE(refresh_token, ''), token_expires_at, COALESCE(history_id, '')
		FROM user_connections WHERE id = $1 AND source_type = 'gmail'`, connectionID).
		Scan(&c.Status, &c.AccessToken, &c.RefreshToken, &c.ExpiresAt, &c.HistoryID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errConnectionNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("load gmail connection: %w", err)
	}
	return &c, nil
}

func (s *pgConnectionStore) SaveTokens(ctx context.Context, connectionID string, u tokenUpdate) error {
	_, err := s.db.Exec(ctx, `
		UPDATE user_connections SET
			access_token = COALESCE($2, access_token),
			refresh_token = COALESCE($3, refresh_token),
			token_expires_at = COALESCE($4, token_expires_at),
			updated_at = now()
		WHERE id = $1`, connectionID, u.AccessToken, u.RefreshToken, u.ExpiresAt)
	if err != nil {
		return fmt.Errorf("save gmail tokens: %w", err)
	}
	return nil
}

func (s *pgConnectionStore) MarkRevoked(ctx context.Context, connectionID, reason string) error {
	_, err := s.db.Exec(ctx, `
		UPDATE user_connections SET status = 'error', last_sync_status = 'failed', last_error = $2, updated_at = now()
		WHERE id = $1`, connectionID, reason)
	if err != nil {
		return fmt.Errorf("mark gmail connection revoked: %w", err)
	}
	return nil
}

func (s *pgConnectionStore) MarkStarted(ctx context.Context, syncSessionID string) error {
	_, err := s.db.Exec(ctx, `
		UPDATE sync_sessions SET status = 'in_progress', started_at = COALESCE(started_at, now()), updated_at = now()
		WHERE id = $1 AND status IN ('queued', 'failed')`, syncSessionID)
	if err != nil {
		return fmt.Errorf("mark sync session started: %w", err)
	}
	return nil
}

func (s *pgConnectionStore) RecordFailure(ctx context.Context, f syncFailure) error {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `
		UPDATE user_connections SET last_sync_status = 'failed', last_error = $2, updated_at = now()
		WHERE id = $1`, f.ConnectionID, f.Reason); err != nil {
		return fmt.Errorf("record connection failure: %w", err)
	}
	if f.SyncSessionID != "" {
		if _, err := tx.Exec(ctx, `
			UPDATE sync_sessions SET status = 'failed', error_details = jsonb_build_object('reason', $2::text), updated_at = now()
			WHERE id = $1 AND status <> 'completed'`, f.SyncSessionID, f.Reason); err != nil {
			return fmt.Errorf("record session failure: %w", err)
		}
	}
	return tx.Commit(ctx)
}
