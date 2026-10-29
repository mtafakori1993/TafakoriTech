package middleware

import (
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/mtafakori/persona/shared/ctxkey"
)

// RequestID ensures every request has a unique ID.
// It reads X-Request-ID from the incoming headers; if absent, a new UUID is generated.
// The ID is stored in the gin context and echoed back in the X-Request-ID response header.
func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = uuid.New().String()
		}
		c.Set(string(ctxkey.RequestIDKey), requestID)
		c.Header("X-Request-ID", requestID)
		c.Next()
	}
}

// TraceID ensures every request has a trace ID for distributed tracing.
// It reads X-Trace-ID from the incoming headers; if absent, a new UUID is generated.
func TraceID() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = uuid.New().String()
		}
		c.Set(string(ctxkey.TraceIDKey), traceID)
		c.Header("X-Trace-ID", traceID)
		c.Next()
	}
}
