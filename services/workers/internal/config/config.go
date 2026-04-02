package config

import "os"

type Config struct {
	DatabaseURL string
	RedisURL    string
	ClaudeAPIKey string
	WorkerType  string
}

func Load() *Config {
	return &Config{
		DatabaseURL:  getEnv("DATABASE_URL", "postgresql://adminos:adminos_dev@localhost:5432/adminos"),
		RedisURL:     getEnv("REDIS_URL", "redis://localhost:6379"),
		ClaudeAPIKey: getEnv("CLAUDE_API_KEY", ""),
		WorkerType:   getEnv("WORKER_TYPE", "all"),
	}
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
