package middleware

import (
	"net/http"
	"runtime/debug"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/shared/middleware/ctxkey"
	"go.uber.org/zap"
)

// Recovery catches panics in downstream handlers, logs a structured error entry
// with the full stack trace, and returns a 500 to the caller without leaking
// internal details.
//
// Always register Recovery before Logger so that panics are logged with the
// complete request context (request_id, trace_id) that Logger would have set.
func Recovery(log *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if r := recover(); r != nil {
				log.Error("panic recovered",
					zap.Any("error", r),
					zap.String("stacktrace", string(debug.Stack())),
					zap.String("request_id", strFromCtx(c, ctxkey.RequestIDKey.String())),
					zap.String("trace_id", strFromCtx(c, ctxkey.TraceIDKey.String())),
					zap.String("user_id", strFromCtx(c, ctxkey.UserIDKey.String())),
					zap.String("method", c.Request.Method),
					zap.String("path", c.Request.URL.Path),
				)

				c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
					"error": "internal server error",
				})
			}
		}()
		c.Next()
	}
}
