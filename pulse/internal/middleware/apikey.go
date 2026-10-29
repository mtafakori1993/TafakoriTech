package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// RequireAPIKey authenticates internal service-to-service requests via X-API-Key header.
func RequireAPIKey(key string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if key == "" || c.GetHeader("X-API-Key") != key {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or missing API key"})
			return
		}
		c.Next()
	}
}
