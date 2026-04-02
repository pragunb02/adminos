package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/adminos/adminos/workers/internal/config"
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

	// TODO: Initialize Asynq server and register handlers (task 3.4.1)
	// server := asynq.NewServer(
	//     asynq.RedisClientOpt{Addr: cfg.RedisURL},
	//     asynq.Config{Concurrency: 10, Queues: map[string]int{"default": 1}},
	// )
	// mux := asynq.NewServeMux()
	// router.RegisterHandlers(mux, cfg)
	// server.Run(mux)

	// Wait for shutdown signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	sig := <-quit
	fmt.Printf("\nReceived %s, shutting down...\n", sig)
}

func maskPassword(url string) string {
	// Simple masking for log output
	if len(url) > 20 {
		return url[:20] + "***"
	}
	return "***"
}
