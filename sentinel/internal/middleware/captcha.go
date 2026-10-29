package middleware

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"
)

// CaptchaVerifier is satisfied by *captcha.Verifier.
type CaptchaVerifier interface {
	Enabled() bool
	Verify(ctx context.Context, token, remoteIP string) error
}

// RequireCaptcha reads the CAPTCHA token from the X-Captcha-Token header and
// validates it.  Requests without a token are rejected when CAPTCHA is enabled.
// Disabled verifiers (provider=off) pass all requests through unchanged.
func RequireCaptcha(v CaptchaVerifier) gin.HandlerFunc {
	return func(c *gin.Context) {
		if v == nil || !v.Enabled() {
			c.Next()
			return
		}
		token := c.GetHeader("X-Captcha-Token")
		if err := v.Verify(c.Request.Context(), token, c.ClientIP()); err != nil {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "captcha verification failed"})
			return
		}
		c.Next()
	}
}
