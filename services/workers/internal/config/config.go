package config

import "os"

type Config struct {
	DatabaseURL string
	RedisURL    string
	// RedisPassword is REDIS_PASSWORD: Redis requires it (AOS-634). It may instead be in RedisURL.
	RedisPassword      string
	WorkerType         string
	GoogleClientID     string
	GoogleClientSecret string
	// TokenEncryptionKey is the API's TOKEN_ENCRYPTION_KEY: the Gmail worker decrypts and encrypts
	// OAuth tokens with it (AOS-401). Required for Gmail ingestion.
	TokenEncryptionKey string
}

func Load() *Config {
	return &Config{
		DatabaseURL:        getEnv("DATABASE_URL", "postgresql://adminos:adminos_dev@localhost:5432/adminos"),
		RedisURL:           getEnv("REDIS_URL", ""), // required (AOS-634)
		RedisPassword:      getEnv("REDIS_PASSWORD", ""),
		WorkerType:         getEnv("WORKER_TYPE", "all"),
		GoogleClientID:     getEnv("GOOGLE_CLIENT_ID", ""),
		GoogleClientSecret: getEnv("GOOGLE_CLIENT_SECRET", ""),
		TokenEncryptionKey: getEnv("TOKEN_ENCRYPTION_KEY", ""),
	}
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
