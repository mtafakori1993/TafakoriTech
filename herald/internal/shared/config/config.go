package config

import (
	"fmt"
	"os"
	"strconv"
)

type Config struct {
	Host      string
	Port      int
	DBURL     string
	APIKey    string
	JWTSecret string
	Env       string
}

func Load() (*Config, error) {
	port, err := strconv.Atoi(firstEnv("HERALD_PORT", "PORT", "9503"))
	if err != nil {
		return nil, fmt.Errorf("invalid PORT: %w", err)
	}
	dbURL := firstEnv("HERALD_DATABASE_URL", "DATABASE_URL")
	if dbURL == "" {
		return nil, fmt.Errorf("DATABASE_URL is required")
	}
	apiKey := firstEnv("HERALD_API_KEY", "API_KEY")
	if apiKey == "" {
		return nil, fmt.Errorf("API_KEY is required")
	}
	return &Config{
		Host:      getEnv("HOST", "0.0.0.0"),
		Port:      port,
		DBURL:     dbURL,
		APIKey:    apiKey,
		JWTSecret: os.Getenv("JWT_SECRET"),
		Env:       getEnv("ENV", "development"),
	}, nil
}

func firstEnv(keys ...string) string {
	for _, k := range keys {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	return ""
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
