package middleware

import (
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/sentinel/internal/logger"
	"github.com/mtafakori/sentinel/internal/metrics"
)

// RequestLogger logs every HTTP request as structured JSON after the response is written.
func RequestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()

		status := c.Writer.Status()
		duration := time.Since(start)

		traceID := GetTraceID(c)
		requestID := GetRequestID(c)
		userID := GetUserID(c)

		event := logger.Log.Info()
		if status >= 500 {
			event = logger.Log.Error()
		} else if status >= 400 {
			event = logger.Log.Warn()
		}

		ev := event.
			Str("trace_id", traceID).
			Str("request_id", requestID).
			Str("method", c.Request.Method).
			Str("path", c.Request.URL.Path).
			Int("status_code", status).
			Int64("duration_ms", duration.Milliseconds()).
			Str("ip", c.ClientIP()).
			Str("user_agent", c.Request.UserAgent())

		if userID != "" {
			ev = ev.Str("user_id", userID)
		}
		if errMsg := c.Errors.ByType(gin.ErrorTypePrivate).String(); errMsg != "" {
			ev = ev.Str("error", errMsg)
		}

		ev.Msg("request")

		metrics.RecordRequest(c.Request.Method, c.Request.URL.Path, status, duration.Seconds())
	}
}
