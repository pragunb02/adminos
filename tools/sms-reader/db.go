package main

import (
	"context"
	"database/sql"
)

// sqlDB is the subset of *sql.DB the reader uses (lets tests use a temp database).
type sqlDB interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}
