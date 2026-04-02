package workflow

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"
)

// JobHandler is the base interface every workflow implements.
// Template Method pattern: Parse → Execute → Persist → Notify
type JobHandler interface {
	JobType() string
	TriggerType() string
	Parse(payload json.RawMessage) (any, error)
	Execute(ctx context.Context, payload any) (any, error)
	Persist(ctx context.Context, result any) error
	Notify(ctx context.Context, result any) error
}

// RunHandler executes a job handler with the standard lifecycle.
// This is the template method — all handlers go through this flow.
func RunHandler(ctx context.Context, handler JobHandler, rawPayload json.RawMessage) error {
	start := time.Now()
	jobType := handler.JobType()

	log.Printf("[%s] Starting job", jobType)

	// Step 1: Parse
	payload, err := handler.Parse(rawPayload)
	if err != nil {
		return fmt.Errorf("[%s] parse failed: %w", jobType, err)
	}

	// Step 2: Execute
	result, err := handler.Execute(ctx, payload)
	if err != nil {
		return fmt.Errorf("[%s] execute failed: %w", jobType, err)
	}

	// Step 3: Persist
	if err := handler.Persist(ctx, result); err != nil {
		return fmt.Errorf("[%s] persist failed: %w", jobType, err)
	}

	// Step 4: Notify (optional — errors logged but don't fail the job)
	if err := handler.Notify(ctx, result); err != nil {
		log.Printf("[%s] notify failed (non-fatal): %v", jobType, err)
	}

	log.Printf("[%s] Completed in %v", jobType, time.Since(start))
	return nil
}
