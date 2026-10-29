package middleware

import (
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/mtafakori/persona/shared/ctxkey"
)

// Auth parses the Authorization: Bearer <token> header and enriches the gin context
// with user_id (sub claim) and role if the token is valid.
// This middleware is permissive — it does NOT reject requests with invalid/missing tokens.
// Kong is responsible for auth enforcement; this middleware only enriches context.
func Auth(jwtSecret string) gin.HandlerFunc {
	secret := []byte(jwtSecret)

	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.Next()
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || !strings.EqualFold(parts[0], "bearer") {
			c.Next()
			return
		}

		tokenStr := parts[1]

		token, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, jwt.ErrSignatureInvalid
			}
			return secret, nil
		})
		if err != nil || !token.Valid {
			c.Next()
			return
		}

		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			c.Next()
			return
		}

		if sub, ok := claims["sub"].(string); ok && sub != "" {
			c.Set(string(ctxkey.AuthUserIDKey), sub)
		}
		if role, ok := claims["role"].(string); ok && role != "" {
			c.Set(string(ctxkey.AuthRoleKey), role)
		}

		c.Next()
	}
}
