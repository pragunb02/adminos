package workflow

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
)

// Router maps job types to their handlers and dispatches incoming jobs.
type Router struct {
	handlers map[string]JobHandler
}

// NewRouter creates a new job router.
func NewRouter() *Router {
	return &Router{
		handlers: make(map[string]JobHandler),
	}
}

// Register adds a handler for a job type.
func (r *Router) Register(handler JobHandler) {
	r.handlers[handler.JobType()] = handler
	log.Printf("Registered handler: %s (trigger=%s)", handler.JobType(), handler.TriggerType())
}

// Dispatch routes a job to the correct handler and executes it.
func (r *Router) Dispatch(ctx context.Context, jobType string, payload json.RawMessage) error {
	handler, ok := r.handlers[jobType]
	if !ok {
		return fmt.Errorf("no handler registered for job type: %s", jobType)
	}
	return RunHandler(ctx, handler, payload)
}

// RegisteredTypes returns all registered job type names.
func (r *Router) RegisteredTypes() []string {
	types := make([]string, 0, len(r.handlers))
	for t := range r.handlers {
		types = append(types, t)
	}
	return types
}
