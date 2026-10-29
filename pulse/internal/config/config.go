package config

import (
	"fmt"
	"os"
	"strconv"
)

type Config struct {
	Host        string
	Port        int
	Env         string
	DBURL       string
	JWTSecret   string
	WorkerCount int
	QueueBuffer int

	SMTPHost string
	SMTPPort int
	SMTPUser string
	SMTPPass string
	SMTPFrom string

	// Internal service-to-service API key (e.g. used by Sentinel to submit OTP notifications)
	PulseAPIKey string

	// SMS provider selection: "smsir" | "ghasedak"
	SMSProvider string

	SMSIRAPIKey        string
	SMSIRLineNumber    string
	SMSIRTemplateID    int
	SMSIROTPParam      string

	GhasedakAPIKey       string
	GhasedakLineNumber   string
	GhasedakTemplateName string
	GhasedakOTPParam     string
}

func Load() (*Config, error) {
	port, _ := strconv.Atoi(firstEnv("PULSE_PORT", "PORT", "9505"))
	smtpPort, _ := strconv.Atoi(getEnv("SMTP_PORT", "587"))
	workerCount, _ := strconv.Atoi(getEnv("WORKER_COUNT", "4"))
	queueBuffer, _ := strconv.Atoi(getEnv("QUEUE_BUFFER", "1000"))

	cfg := &Config{
		Host:        getEnv("HOST", "0.0.0.0"),
		Port:        port,
		Env:         getEnv("ENV", "development"),
		DBURL:       firstEnv("PULSE_DATABASE_URL", "DATABASE_URL"),
		JWTSecret:   os.Getenv("JWT_SECRET"),
		WorkerCount: workerCount,
		QueueBuffer: queueBuffer,
		SMTPHost:    os.Getenv("SMTP_HOST"),
		SMTPPort:    smtpPort,
		SMTPUser:    os.Getenv("SMTP_USER"),
		SMTPPass:    loadSecret("SMTP_PASS", "/run/secrets/smtp_pass"),
		SMTPFrom:    getEnv("SMTP_FROM", "no-reply@tafakori.tech"),
		PulseAPIKey:        loadSecret("PULSE_API_KEY", "/run/secrets/pulse_api_key"),
		SMSProvider:        getEnv("SMS_PROVIDER", "smsir"),
		SMSIRAPIKey:          loadSecret("SMSIR_API_KEY", "/run/secrets/smsir_api_key"),
		SMSIRLineNumber:      os.Getenv("SMSIR_LINE_NUMBER"),
		SMSIRTemplateID:      parseSMSIRTemplateID(os.Getenv("SMSIR_TEMPLATE_ID")),
		SMSIROTPParam:        getEnv("SMSIR_OTP_PARAM", "OTP"),
		GhasedakAPIKey:       loadSecret("GHASEDAK_API_KEY", "/run/secrets/ghasedak_api_key"),
		GhasedakLineNumber:   os.Getenv("GHASEDAK_LINE_NUMBER"),
		GhasedakTemplateName: getEnv("GHASEDAK_TEMPLATE_NAME", "tafakoritech"),
		GhasedakOTPParam:     getEnv("GHASEDAK_OTP_PARAM", "Code"),
	}

	if cfg.DBURL == "" {
		return nil, fmt.Errorf("DATABASE_URL is required")
	}
	if cfg.JWTSecret == "" {
		return nil, fmt.Errorf("JWT_SECRET is required")
	}
	return cfg, nil
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

func parseSMSIRTemplateID(s string) int {
	if v, err := strconv.Atoi(s); err == nil {
		return v
	}
	return 0
}

func loadSecret(envKey, secretPath string) string {
	if b, err := os.ReadFile(secretPath); err == nil {
		v := string(b)
		for len(v) > 0 && (v[len(v)-1] == '\n' || v[len(v)-1] == '\r') {
			v = v[:len(v)-1]
		}
		return v
	}
	return os.Getenv(envKey)
}
