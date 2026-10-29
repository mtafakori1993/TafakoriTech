package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/pulse/internal/logger"
)

func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if r := recover(); r != nil {
				logger.Log.Error().
					Str("trace_id", GetTraceID(c)).
					Str("path", c.Request.URL.Path).
					Interface("panic", r).
					Msg("panic recovered")

				c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
					"error": "internal_server_error",
				})
			}
		}()
		c.Next()
	}
}
