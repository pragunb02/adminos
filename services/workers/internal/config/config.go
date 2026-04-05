package config

import "os"

type Config struct {
	DatabaseURL        string
	RedisURL           string
	ClaudeAPIKey       string
	ClaudeModel        string
	WorkerType         string
	GoogleClientID     string
	GoogleClientSecret string
}

func Load() *Config {
	return &Config{
		DatabaseURL:        getEnv("DATABASE_URL", "postgresql://adminos:adminos_dev@localhost:5432/adminos"),
		RedisURL:           getEnv("REDIS_URL", "redis://localhost:6379"),
		ClaudeAPIKey:       getEnv("CLAUDE_API_KEY", ""),
		ClaudeModel:        getEnv("CLAUDE_MODEL", "claude-sonnet-4-20250514"),
		WorkerType:         getEnv("WORKER_TYPE", "all"),
		GoogleClientID:     getEnv("GOOGLE_CLIENT_ID", ""),
		GoogleClientSecret: getEnv("GOOGLE_CLIENT_SECRET", ""),
	}
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
