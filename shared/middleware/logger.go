package middleware

import (
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/shared/middleware/ctxkey"
	"go.uber.org/zap"
)

// sensitiveHeaders must never appear in logs.
// checked as lowercased strings.
var sensitiveHeaders = map[string]struct{}{
	"authorization": {},
	"x-api-key":    {},
	"cookie":        {},
	"set-cookie":    {},
	"x-auth-token":  {},
}

// Logger returns a Gin middleware that logs every HTTP request after it completes.
// Required fields per the TafakoriTech logging standard:
//
//	timestamp   — from zap encoder config (ISO-8601)
//	level       — info for <400, warn for 4xx, error for 5xx
//	service     — from logger.With("service", ...) at startup
//	trace_id    — from X-Trace-ID header / TraceID middleware
//	request_id  — from X-Request-ID header / RequestID middleware
//	user_id     — from JWT / Auth middleware (omitted when empty)
//	method      — HTTP method
//	path        — URL path (no query string to avoid leaking params)
//	status_code — response status
//	duration_ms — wall-clock latency in milliseconds (float, 3 decimal places)
//	message     — "request completed"
//	error       — gin error chain (omitted when empty)
func Logger(log *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path

		c.Next()

		duration := time.Since(start)
		status := c.Writer.Status()

		fields := []zap.Field{
			zap.String("request_id", strFromCtx(c, ctxkey.RequestIDKey.String())),
			zap.String("trace_id", strFromCtx(c, ctxkey.TraceIDKey.String())),
			zap.String("method", c.Request.Method),
			zap.String("path", path),
			zap.Int("status_code", status),
			zap.Float64("duration_ms", float64(duration.Microseconds())/1000.0),
			zap.String("ip", c.ClientIP()),
			zap.String("user_agent", sanitizeUserAgent(c.Request.UserAgent())),
		}

		if uid := strFromCtx(c, ctxkey.UserIDKey.String()); uid != "" {
			fields = append(fields, zap.String("user_id", uid))
		}

		if errs := c.Errors.ByType(gin.ErrorTypePrivate).String(); errs != "" {
			fields = append(fields, zap.String("error", errs))
		}

		switch {
		case status >= 500:
			log.Error("request completed", fields...)
		case status >= 400:
			log.Warn("request completed", fields...)
		default:
			log.Info("request completed", fields...)
		}
	}
}

// strFromCtx reads a string value set by an upstream middleware from the gin context.
func strFromCtx(c *gin.Context, key string) string {
	v, _ := c.Get(key)
	s, _ := v.(string)
	return s
}

// sanitizeUserAgent truncates overly long user agent strings.
func sanitizeUserAgent(ua string) string {
	if len(ua) > 256 {
		return ua[:256]
	}
	return ua
}

// IsSensitiveHeader reports whether a header name should be redacted from logs.
func IsSensitiveHeader(name string) bool {
	_, ok := sensitiveHeaders[strings.ToLower(name)]
	return ok
}
