package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/hibiken/asynq"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"github.com/adminos/adminos/workers/internal/claude"
	"github.com/adminos/adminos/workers/internal/config"
	"github.com/adminos/adminos/workers/internal/gmail"
	"github.com/adminos/adminos/workers/internal/handlers"
	"github.com/adminos/adminos/workers/internal/workflow"
)

func main() {
	workerType := flag.String("type", "all", "Worker type: gmail, sms, agent, all")
	flag.Parse()

	cfg := config.Load()
	if *workerType != "all" {
		cfg.WorkerType = *workerType
	}

	log.Printf("Starting AdminOS worker (type=%s)", cfg.WorkerType)
	log.Printf("Redis: %s", cfg.RedisURL)
	log.Printf("Database: %s", maskPassword(cfg.DatabaseURL))

	redisAddr := parseRedisAddr(cfg.RedisURL)

	// Initialize pgx connection pool (nil if DATABASE_URL is empty)
	var db *pgxpool.Pool
	if cfg.DatabaseURL != "" {
		var err error
		db, err = pgxpool.New(context.Background(), cfg.DatabaseURL)
		if err != nil {
			log.Printf("WARNING: Failed to connect to database: %v — running in degraded mode", err)
		} else {
			log.Printf("Database connection pool initialized")
		}
	} else {
		log.Println("DATABASE_URL not configured — running in degraded mode (no persistence)")
	}
	if db != nil {
		defer db.Close()
	}

	// Create workflow router and register handlers
	router := workflow.NewRouter()

	// Initialize Claude client if API key is configured
	var claudeClient *claude.Client
	if cfg.ClaudeAPIKey != "" {
		claudeClient = claude.NewClient(cfg.ClaudeAPIKey, cfg.ClaudeModel)
		log.Printf("Claude API client initialized (model=%s)", cfg.ClaudeModel)
	} else {
		log.Println("Claude API key not configured — agent handlers will use stub responses")
	}

	// Initialize Gmail API client
	gmailClient := gmail.NewClient()

	registerHandlers(router, cfg.WorkerType, db, claudeClient, gmailClient, cfg.GoogleClientID, cfg.GoogleClientSecret)

	// Create Asynq server
	srv := asynq.NewServer(
		asynq.RedisClientOpt{Addr: redisAddr},
		asynq.Config{
			Concurrency: 10,
			Queues:      map[string]int{"default": 1},
			RetryDelayFunc: asynq.DefaultRetryDelayFunc,
		},
	)

	// Bridge: Asynq ServeMux → workflow Router
	mux := asynq.NewServeMux()
	for _, jobType := range router.RegisteredTypes() {
		jt := jobType
		mux.HandleFunc(jt, func(ctx context.Context, task *asynq.Task) error {
			return router.Dispatch(ctx, jt, task.Payload())
		})
	}

	// Graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	// Start bridge: reads from Kotlin's Redis list → enqueues via Asynq client
	asynqClient := asynq.NewClient(asynq.RedisClientOpt{Addr: redisAddr})
	rdb := redis.NewClient(&redis.Options{Addr: redisAddr})
	go bridgeKotlinJobs(ctx, rdb, asynqClient)

	// Start Asynq server
	go func() {
		if err := srv.Run(mux); err != nil {
			log.Printf("Asynq server error: %v", err)
		}
	}()

	sig := <-quit
	fmt.Printf("\nReceived %s, shutting down...\n", sig)
	cancel()
	srv.Shutdown()
	asynqClient.Close()
	rdb.Close()
	log.Println("Worker shut down gracefully")
}

// registerHandlers registers job handlers based on worker type.
func registerHandlers(router *workflow.Router, workerType string, db *pgxpool.Pool, claudeClient *claude.Client, gmailClient *gmail.Client, googleClientID, googleClientSecret string) {
	switch workerType {
	case "sms":
		router.Register(handlers.NewSmsHandler(db))
	case "gmail":
		router.Register(handlers.NewGmailHandler(db, gmailClient, googleClientID, googleClientSecret))
	case "pdf":
		router.Register(handlers.NewPdfHandler(db))
	case "agent":
		router.Register(handlers.NewBriefingHandler(db, claudeClient))
		router.Register(handlers.NewAnomalyExplainHandler(db, claudeClient))
		router.Register(handlers.NewWasteScoringHandler(db))
	case "all":
		router.Register(handlers.NewSmsHandler(db))
		router.Register(handlers.NewGmailHandler(db, gmailClient, googleClientID, googleClientSecret))
		router.Register(handlers.NewPdfHandler(db))
		router.Register(handlers.NewBriefingHandler(db, claudeClient))
		router.Register(handlers.NewAnomalyExplainHandler(db, claudeClient))
		router.Register(handlers.NewWasteScoringHandler(db))
	}
}

// bridgeKotlinJobs reads JSON job messages from the Kotlin publisher's Redis list
// and re-enqueues them via Asynq's native client. This avoids coupling Kotlin
// to Asynq's internal protobuf wire format.
func bridgeKotlinJobs(ctx context.Context, rdb *redis.Client, client *asynq.Client) {
	const listKey = "adminos:jobs:pending"
	log.Printf("[bridge] Listening for Kotlin-published jobs on %s", listKey)

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		// BRPOP with 1s timeout so we can check ctx.Done() periodically
		result, err := rdb.BRPop(ctx, 1*time.Second, listKey).Result()
		if err != nil {
			if err == redis.Nil || strings.Contains(err.Error(), "context canceled") {
				continue
			}
			log.Printf("[bridge] Redis error: %v", err)
			time.Sleep(1 * time.Second)
			continue
		}

		if len(result) < 2 {
			continue
		}

		// Parse the JSON message from Kotlin
		var msg struct {
			ID      string `json:"id"`
			Type    string `json:"type"`
			Payload string `json:"payload"`
			Queue   string `json:"queue"`
			Retry   string `json:"retry"`
			Timeout string `json:"timeout"`
		}
		if err := json.Unmarshal([]byte(result[1]), &msg); err != nil {
			log.Printf("[bridge] Failed to parse message: %v", err)
			continue
		}

		// Enqueue via Asynq's native client
		task := asynq.NewTask(msg.Type, []byte(msg.Payload))
		opts := []asynq.Option{
			asynq.MaxRetry(3),
			asynq.Timeout(5 * time.Minute),
		}
		if msg.Queue != "" {
			opts = append(opts, asynq.Queue(msg.Queue))
		}

		info, err := client.Enqueue(task, opts...)
		if err != nil {
			log.Printf("[bridge] Failed to enqueue %s: %v", msg.Type, err)
			continue
		}

		log.Printf("[bridge] Enqueued %s → asynq (id=%s, queue=%s)", msg.Type, info.ID, info.Queue)
	}
}

// parseRedisAddr extracts host:port from a Redis URL.
func parseRedisAddr(url string) string {
	addr := strings.TrimPrefix(url, "redis://")
	addr = strings.TrimPrefix(addr, "rediss://")
	if idx := strings.LastIndex(addr, "@"); idx >= 0 {
		addr = addr[idx+1:]
	}
	if idx := strings.Index(addr, "/"); idx >= 0 {
		addr = addr[:idx]
	}
	if addr == "" {
		return "localhost:6379"
	}
	return addr
}

func maskPassword(url string) string {
	if len(url) > 20 {
		return url[:20] + "***"
	}
	return "***"
}
