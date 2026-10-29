package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

// APIKey rejects requests whose X-API-Key header does not match the expected key.
func APIKey(expected string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if expected == "" || c.GetHeader("X-API-Key") != expected {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
			return
		}
		c.Next()
	}
}
