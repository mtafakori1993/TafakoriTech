package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/sentinel/internal/logger"
)

// Recovery replaces gin.Recovery() with structured JSON panic logging.
func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if r := recover(); r != nil {
				logger.Log.Error().
					Str("trace_id", GetTraceID(c)).
					Str("request_id", GetRequestID(c)).
					Str("path", c.Request.URL.Path).
					Interface("panic", r).
					Msg("panic recovered")

				c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
					"error":   "internal_server_error",
					"message": "An unexpected error occurred",
				})
			}
		}()
		c.Next()
	}
}
