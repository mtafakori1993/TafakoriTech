package config

import (
	"fmt"
	"os"
)

// Config holds all application configuration loaded from environment variables.
type Config struct {
	DatabaseURL     string
	JWTSecret       string
	APIKey          string
	Port            string
	Host            string
	Env             string
	SwaggerHost     string
	SwaggerBasePath string
}

// Load reads configuration from environment variables.
// DATABASE_URL (or PERSONA_DATABASE_URL) and JWT_SECRET are required.
func Load() (*Config, error) {
	dbURL := firstEnv("PERSONA_DATABASE_URL", "DATABASE_URL")
	if dbURL == "" {
		return nil, fmt.Errorf("DATABASE_URL is required")
	}

	jwtSecret := os.Getenv("JWT_SECRET")
	if jwtSecret == "" {
		return nil, fmt.Errorf("JWT_SECRET is required")
	}

	port := firstEnv("PERSONA_PORT", "PORT")
	if port == "" {
		port = "9502"
	}

	host := os.Getenv("HOST")
	if host == "" {
		host = "0.0.0.0"
	}

	env := os.Getenv("ENV")
	if env == "" {
		env = "development"
	}

	return &Config{
		DatabaseURL:     dbURL,
		JWTSecret:       jwtSecret,
		APIKey:          firstEnv("PERSONA_API_KEY", "API_KEY"),
		Port:            port,
		Host:            host,
		Env:             env,
		SwaggerHost:     os.Getenv("SWAGGER_HOST"),
		SwaggerBasePath: os.Getenv("SWAGGER_BASE_PATH"),
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
