package logger

import (
	"os"
	"time"

	"github.com/rs/zerolog"
)

var Log zerolog.Logger

func Init(serviceName string) {
	zerolog.TimestampFieldName = "timestamp"
	zerolog.LevelFieldName = "level"
	zerolog.MessageFieldName = "message"
	zerolog.TimeFieldFormat = time.RFC3339 // matches zap_json Fluent Bit parser format

	Log = zerolog.New(os.Stdout).With().
		Timestamp().
		Str("service", serviceName).
		Str("log_type", "application").
		Logger()
}

func With(traceID, requestID string) zerolog.Logger {
	return Log.With().
		Str("trace_id", traceID).
		Str("request_id", requestID).
		Logger()
}
