package middleware

import (
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

type accessClaims struct {
	UserID      int64  `json:"uid"`
	Role        string `json:"role"`
	SessionUUID string `json:"sid"`
	TokenType   string `json:"typ"`
	jwt.RegisteredClaims
}

// RequireAuth validates the Bearer JWT issued by Sentinel using the shared secret.
// Does not check the session blacklist — Pulse is a fire-and-forget notification service.
func RequireAuth(secret []byte) gin.HandlerFunc {
	return func(c *gin.Context) {
		auth := c.GetHeader("Authorization")
		if !strings.HasPrefix(auth, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing or invalid authorization header"})
			return
		}
		raw := strings.TrimPrefix(auth, "Bearer ")

		token, err := jwt.ParseWithClaims(raw, &accessClaims{}, func(t *jwt.Token) (any, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
			}
			return secret, nil
		})
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired token"})
			return
		}

		cl, ok := token.Claims.(*accessClaims)
		if !ok || cl.UserID == 0 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token claims"})
			return
		}
		if cl.TokenType == "refresh" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "cannot use refresh token as access token"})
			return
		}

		c.Set(KeyUserID, fmt.Sprintf("%d", cl.UserID))
		c.Set(KeyRole, cl.Role)
		c.Next()
	}
}
