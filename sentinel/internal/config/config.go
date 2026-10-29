package config

import (
	"encoding/base64"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

type RateLimitRule struct {
	Limit  int
	Window time.Duration
}

type RateLimits struct {
	OTPRequest     RateLimitRule
	OTPVerify      RateLimitRule
	Login          RateLimitRule
	Refresh        RateLimitRule
	MFAChallenge   RateLimitRule
	MFASetup       RateLimitRule
	RecoveryVerify RateLimitRule
	DeviceTrust    RateLimitRule
}

type Config struct {
	DBURL           string
	JWTSecret       string
	PersonaURL      string
	PersonaAPIKey   string
	PulseURL        string
	PulseAPIKey     string
	Host            string
	Port            int
	Env             string
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
	SessionMaxAge   time.Duration

	RedisURL   string
	RateLimits RateLimits

	// Failed-login tracking and auto account-lock
	FailWarnAt   int           // log warning after this many failures
	FailLockAt   int           // auto-lock after this many failures
	FailWindow   time.Duration // sliding counter TTL
	LockDuration time.Duration // auto-lock duration (0 = permanent)

	// MFA / TOTP
	MFAEncryptionKey []byte        // 32 bytes (AES-256-GCM) for TOTP secret encryption at rest
	RecoveryPepper   []byte        // server-side pepper mixed into recovery-code hashes
	DeviceSecret     []byte        // HMAC key for signing trusted-device tokens
	TOTPIssuer       string        // label shown in authenticator apps
	TOTPSkew         uint          // allowed ± time steps (1 = ±30s)
	MFAChallengeTTL  time.Duration // life of the intermediate mfa_challenge token
	DeviceTrustTTL   time.Duration // how long a trusted device bypasses MFA

	// Altcha (self-hosted proof-of-work CAPTCHA)
	AltchaHMACKey   string        // HMAC-SHA256 secret; empty = disabled
	AltchaMaxNumber int64         // proof-of-work complexity (higher = harder)
	AltchaExpires   time.Duration // challenge validity window

}

func Load() (*Config, error) {
	port, _ := strconv.Atoi(firstEnv("SENTINEL_PORT", "PORT", "9501"))
	accessMins, _ := strconv.Atoi(getEnv("ACCESS_TOKEN_TTL_MINUTES", "60"))
	refreshDays, _ := strconv.Atoi(getEnv("REFRESH_TOKEN_TTL_DAYS", "7"))
	sessionDays, _ := strconv.Atoi(getEnv("SESSION_MAX_AGE_DAYS", "90"))
	failWarn, _ := strconv.Atoi(getEnv("FAIL_WARN_AT", "5"))
	failLock, _ := strconv.Atoi(getEnv("FAIL_LOCK_AT", "10"))
	totpSkew, _ := strconv.Atoi(getEnv("TOTP_SKEW", "1"))
	deviceTrustDays, _ := strconv.Atoi(getEnv("DEVICE_TRUST_TTL_DAYS", "30"))

	cfg := &Config{
		DBURL:           firstEnv("SENTINEL_DATABASE_URL", "DATABASE_URL"),
		JWTSecret:       os.Getenv("JWT_SECRET"),
		PersonaURL:      getEnv("PERSONA_URL", "http://persona:8095"),
		PersonaAPIKey:   os.Getenv("PERSONA_API_KEY"),
		PulseURL:        getEnv("PULSE_URL", "http://pulse:9505"),
		PulseAPIKey:     loadKeyString("PULSE_API_KEY", "/run/secrets/pulse_api_key"),
		Host:            getEnv("HOST", "0.0.0.0"),
		Port:            port,
		Env:             getEnv("ENV", "development"),
		AccessTokenTTL:  time.Duration(accessMins) * time.Minute,
		RefreshTokenTTL: time.Duration(refreshDays) * 24 * time.Hour,
		SessionMaxAge:   time.Duration(sessionDays) * 24 * time.Hour,
		RedisURL:        getEnv("REDIS_URL", "redis://redis:6379"),
		RateLimits: RateLimits{
			OTPRequest:     parseRule("RATE_LIMIT_OTP_REQUEST", 5, 10*time.Minute),
			OTPVerify:      parseRule("RATE_LIMIT_OTP_VERIFY", 10, 10*time.Minute),
			Login:          parseRule("RATE_LIMIT_LOGIN", 20, time.Hour),
			Refresh:        parseRule("RATE_LIMIT_REFRESH", 30, time.Hour),
			MFAChallenge:   parseRule("RATE_LIMIT_MFA_CHALLENGE", 10, 5*time.Minute),
			MFASetup:       parseRule("RATE_LIMIT_MFA_SETUP", 5, time.Hour),
			RecoveryVerify: parseRule("RATE_LIMIT_RECOVERY_VERIFY", 5, 15*time.Minute),
			DeviceTrust:    parseRule("RATE_LIMIT_DEVICE_TRUST", 10, time.Hour),
		},
		FailWarnAt:   failWarn,
		FailLockAt:   failLock,
		FailWindow:   parseDuration("FAIL_WINDOW", 10*time.Minute),
		LockDuration: parseDuration("LOCK_DURATION", 30*time.Minute),

		AltchaHMACKey:   loadKeyString("ALTCHA_HMAC_KEY", "/run/secrets/altcha_hmac_key"),
		AltchaMaxNumber: parseAltchaMaxNumber(getEnv("ALTCHA_MAX_NUMBER", "50000")),
		AltchaExpires:   parseDuration("ALTCHA_EXPIRES", 10*time.Minute),

		TOTPIssuer: getEnv("TOTP_ISSUER", "Tafakori"),
		TOTPSkew:        uint(totpSkew),
		MFAChallengeTTL: parseDuration("MFA_CHALLENGE_TTL", 5*time.Minute),
		DeviceTrustTTL:  time.Duration(deviceTrustDays) * 24 * time.Hour,

	}

	// Key material: prefer Swarm secrets (files), fall back to env for local dev.
	cfg.MFAEncryptionKey = loadKey("MFA_ENCRYPTION_KEY", "/run/secrets/mfa_encryption_key")
	cfg.RecoveryPepper = loadKey("RECOVERY_PEPPER", "/run/secrets/recovery_pepper")
	cfg.DeviceSecret = loadKey("DEVICE_SECRET", "/run/secrets/device_secret")

	if cfg.DBURL == "" {
		return nil, fmt.Errorf("DATABASE_URL is required")
	}
	if cfg.JWTSecret == "" {
		return nil, fmt.Errorf("JWT_SECRET is required")
	}
	if len(cfg.MFAEncryptionKey) != 32 {
		return nil, fmt.Errorf("MFA_ENCRYPTION_KEY must be exactly 32 bytes (got %d)", len(cfg.MFAEncryptionKey))
	}
	if len(cfg.RecoveryPepper) == 0 {
		return nil, fmt.Errorf("RECOVERY_PEPPER is required")
	}
	if len(cfg.DeviceSecret) == 0 {
		return nil, fmt.Errorf("DEVICE_SECRET is required")
	}
	return cfg, nil
}

// loadKey reads key material from a Swarm secret file if present, otherwise from
// an env var. A "base64:" prefix (in either source) is decoded; raw bytes are
// used as-is. Trailing newlines from `docker secret create` are trimmed.
func loadKey(envKey, secretPath string) []byte {
	var raw string
	if b, err := os.ReadFile(secretPath); err == nil {
		raw = strings.TrimRight(string(b), "\r\n")
	} else {
		raw = os.Getenv(envKey)
	}
	if raw == "" {
		return nil
	}
	if rest, ok := strings.CutPrefix(raw, "base64:"); ok {
		if decoded, err := base64.StdEncoding.DecodeString(rest); err == nil {
			return decoded
		}
	}
	return []byte(raw)
}

// loadKeyString is like loadKey but returns a string (for non-binary secrets like HMAC keys).
func loadKeyString(envKey, secretPath string) string {
	if b, err := os.ReadFile(secretPath); err == nil {
		return strings.TrimRight(string(b), "\r\n")
	}
	return os.Getenv(envKey)
}

func parseRule(prefix string, defaultLimit int, defaultWindow time.Duration) RateLimitRule {
	limit := defaultLimit
	window := defaultWindow
	if v := os.Getenv(prefix + "_LIMIT"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = n
		}
	}
	if v := os.Getenv(prefix + "_WINDOW"); v != "" {
		if d, err := time.ParseDuration(v); err == nil && d > 0 {
			window = d
		}
	}
	return RateLimitRule{Limit: limit, Window: window}
}

func parseDuration(key string, fallback time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil && d > 0 {
			return d
		}
	}
	return fallback
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func firstEnv(keys ...string) string {
	for _, k := range keys {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	return ""
}

func parseAltchaMaxNumber(s string) int64 {
	if v, err := strconv.ParseInt(s, 10, 64); err == nil && v > 0 {
		return v
	}
	return 1_000_000
}
