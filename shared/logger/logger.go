// Package logger provides a structured JSON logger built on Uber Zap.
// Every log entry automatically includes the service name; all other
// per-request fields (trace_id, request_id, user_id, …) are added by the
// middleware package using zap.Logger.With().
package logger

import (
	"os"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

// Config controls logger creation.
type Config struct {
	// ServiceName is stamped on every log line as the "service" field.
	ServiceName string
	// Level is a string representation of the log level: debug, info, warn, error.
	// Defaults to "info" on invalid input.
	Level string
	// Env drives encoder choice: "production" → JSON-only, everything else →
	// JSON with caller and development-friendly defaults.
	Env string
}

// New builds a *zap.Logger that writes structured JSON to stdout.
// The returned logger is safe for concurrent use and has no background
// goroutines — call Sync() on shutdown to flush any buffered output.
func New(cfg Config) *zap.Logger {
	encCfg := zapcore.EncoderConfig{
		TimeKey:        "timestamp",
		LevelKey:       "level",
		NameKey:        "logger",
		CallerKey:      "caller",
		FunctionKey:    zapcore.OmitKey,
		MessageKey:     "message",
		StacktraceKey:  "stacktrace",
		LineEnding:     zapcore.DefaultLineEnding,
		EncodeLevel:    zapcore.LowercaseLevelEncoder,
		EncodeTime:     zapcore.ISO8601TimeEncoder,
		EncodeDuration: zapcore.MillisDurationEncoder,
		EncodeCaller:   zapcore.ShortCallerEncoder,
	}

	core := zapcore.NewCore(
		zapcore.NewJSONEncoder(encCfg),
		zapcore.AddSync(os.Stdout),
		parseLevel(cfg.Level),
	)

	opts := []zap.Option{
		zap.AddCaller(),
		// Attach stacktrace automatically on Error and above
		zap.AddStacktrace(zapcore.ErrorLevel),
		// Stamp every line with the service name
		zap.Fields(zap.String("service", cfg.ServiceName)),
	}

	return zap.New(core, opts...)
}

// With is a convenience wrapper that calls New and then applies additional
// fields that are static for the lifetime of the process (e.g. version, env).
func With(cfg Config, fields ...zap.Field) *zap.Logger {
	return New(cfg).With(fields...)
}

func parseLevel(s string) zapcore.Level {
	l, err := zapcore.ParseLevel(s)
	if err != nil {
		return zapcore.InfoLevel
	}
	return l
}
