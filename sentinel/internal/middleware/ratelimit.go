package middleware

import (
	"fmt"
	"math"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/gin-gonic/gin/binding"
	"github.com/mtafakori/sentinel/internal/audit"
	"github.com/mtafakori/sentinel/internal/logger"
	"github.com/mtafakori/sentinel/internal/metrics"
	"github.com/mtafakori/sentinel/internal/ratelimit"
)

// RateLimiter groups middleware constructors for the three limit strategies.
type RateLimiter struct {
	limiter *ratelimit.Limiter
	audit   *audit.Logger
}

func NewRateLimiter(limiter *ratelimit.Limiter, auditLog *audit.Logger) *RateLimiter {
	return &RateLimiter{limiter: limiter, audit: auditLog}
}

// ByIP limits requests per IP address scoped to the endpoint path.
func (m *RateLimiter) ByIP(rule ratelimit.Rule) gin.HandlerFunc {
	return func(c *gin.Context) {
		ip := c.ClientIP()
		key := ip + ":" + c.Request.URL.Path
		result, _ := m.limiter.Allow(c.Request.Context(), "ip", key, rule)
		if !result.Allowed {
			m.reject(c, "ip", ip, rule)
			return
		}
		c.Next()
	}
}

// ByUser limits requests per authenticated user ID (read from Gin context).
func (m *RateLimiter) ByUser(rule ratelimit.Rule) gin.HandlerFunc {
	return func(c *gin.Context) {
		userID := GetUserID(c)
		if userID == "" {
			c.Next()
			return
		}
		key := userID + ":" + c.Request.URL.Path
		result, _ := m.limiter.Allow(c.Request.Context(), "user", key, rule)
		if !result.Allowed {
			m.reject(c, "user", userID, rule)
			return
		}
		c.Next()
	}
}

// ByIdentifier limits requests per email/phone identifier from the JSON body.
// Uses gin's body caching (ShouldBindBodyWith) so subsequent handler reads work.
func (m *RateLimiter) ByIdentifier(rule ratelimit.Rule) gin.HandlerFunc {
	return func(c *gin.Context) {
		var body struct {
			Identifier string `json:"identifier"`
		}
		if err := c.ShouldBindBodyWith(&body, binding.JSON); err != nil || body.Identifier == "" {
			c.Next()
			return
		}

		key := body.Identifier + ":" + c.Request.URL.Path
		result, _ := m.limiter.Allow(c.Request.Context(), "identifier", key, rule)
		if !result.Allowed {
			m.reject(c, "identifier", body.Identifier, rule)
			return
		}
		c.Next()
	}
}

func (m *RateLimiter) reject(c *gin.Context, limitType, key string, rule ratelimit.Rule) {
	retryAfterSec := int(math.Ceil(rule.Window.Seconds()))

	traceID := GetTraceID(c)
	requestID := GetRequestID(c)
	endpoint := c.Request.URL.Path

	logger.Log.Warn().
		Str("trace_id", traceID).
		Str("request_id", requestID).
		Str("event", "rate_limit_exceeded").
		Str("limit_type", limitType).
		Str("key", key).
		Str("endpoint", endpoint).
		Int("limit", rule.Limit).
		Str("window", rule.Window.String()).
		Msg("rate limit exceeded")

	m.audit.RateLimitHit(limitType, key, endpoint, c.ClientIP(), traceID, requestID, rule.Limit, rule.Window.String())
	metrics.RecordRateLimit(limitType, endpoint)

	c.Header("Retry-After", fmt.Sprint(retryAfterSec))
	c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
		"error":       "rate_limit_exceeded",
		"message":     "Too many requests. Please try again later.",
		"retry_after": retryAfterSec,
	})
}
