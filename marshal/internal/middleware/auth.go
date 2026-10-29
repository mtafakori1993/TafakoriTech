package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// APIKeyAuth rejects requests that don't carry the expected key in
// X-API-Key header (or api_key query parameter as fallback).
func APIKeyAuth(apiKey string) gin.HandlerFunc {
	return func(c *gin.Context) {
		key := c.GetHeader("X-API-Key")
		if key == "" {
			key = c.Query("api_key")
		}
		if key != apiKey {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
			return
		}
		c.Next()
	}
}
