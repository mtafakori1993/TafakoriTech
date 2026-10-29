package middleware

import (
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/persona/shared/ctxkey"
	"go.uber.org/zap"
)

// Logger returns a gin middleware that logs each request using the provided zap logger.
// Fields logged: method, path, status, latency, request_id.
func Logger(log *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()

		c.Next()

		requestID, _ := c.Get(string(ctxkey.RequestIDKey))
		traceID, _ := c.Get(string(ctxkey.TraceIDKey))

		log.Info("request",
			zap.String("method", c.Request.Method),
			zap.String("path", c.Request.URL.Path),
			zap.Int("status", c.Writer.Status()),
			zap.Duration("latency", time.Since(start)),
			zap.Any("request_id", requestID),
			zap.Any("trace_id", traceID),
		)
	}
}
