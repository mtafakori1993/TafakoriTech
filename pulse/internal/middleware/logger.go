package middleware

import (
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/pulse/internal/logger"
)

func RequestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()

		status := c.Writer.Status()
		duration := time.Since(start)

		event := logger.Log.Info()
		if status >= 500 {
			event = logger.Log.Error()
		} else if status >= 400 {
			event = logger.Log.Warn()
		}

		ev := event.
			Str("trace_id", GetTraceID(c)).
			Str("request_id", GetRequestID(c)).
			Str("method", c.Request.Method).
			Str("path", c.Request.URL.Path).
			Int("status_code", status).
			Int64("duration_ms", duration.Milliseconds()).
			Str("ip", c.ClientIP())

		if uid := GetUserID(c); uid != "" {
			ev = ev.Str("user_id", uid)
		}
		if errMsg := c.Errors.ByType(gin.ErrorTypePrivate).String(); errMsg != "" {
			ev = ev.Str("error", errMsg)
		}

		ev.Msg("request")
	}
}
