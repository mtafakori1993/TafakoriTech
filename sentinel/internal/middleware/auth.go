package middleware

import (
	"context"
	"fmt"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// SessionChecker is satisfied by *blacklist.Blacklist.
// Defined here to avoid an import cycle (middleware → blacklist → redis).
type SessionChecker interface {
	IsSessionRevoked(ctx context.Context, sessionUUID string) bool
}

type accessClaims struct {
	UserID      int64  `json:"uid"`
	Role        string `json:"role"`
	SessionUUID string `json:"sid"`
	TokenType   string `json:"typ"`
	jwt.RegisteredClaims
}

// RequireAuth validates the Bearer JWT, rejects refresh tokens, and checks
// the session blacklist for immediate post-revocation enforcement.
// Sets KeyUserID, KeyRole, and KeySessionUUID in the gin context.
func RequireAuth(secret []byte, bl SessionChecker) gin.HandlerFunc {
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

		// Immediate revocation check: session was logged out / revoked after this
		// token was issued but before it expired.
		if bl != nil && bl.IsSessionRevoked(c.Request.Context(), cl.SessionUUID) {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "session revoked"})
			return
		}

		c.Set(KeyUserID, fmt.Sprintf("%d", cl.UserID))
		c.Set(KeyRole, cl.Role)
		c.Set(KeySessionUUID, cl.SessionUUID)
		c.Next()
	}
}

// RequireRole aborts with 403 if the caller's role does not match.
// Must be placed after RequireAuth.
func RequireRole(role string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if r := getString(c, KeyRole); r != role {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "insufficient permissions"})
			return
		}
		c.Next()
	}
}
