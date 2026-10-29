package middleware

import (
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

const (
	KeyTraceID   = "trace_id"
	KeyRequestID = "request_id"
	KeyUserID    = "user_id"
	KeyRole        = "role"
	KeySessionUUID = "session_uuid"
)

// Tracing injects trace_id and request_id into every request.
// Accepts X-Trace-ID from upstream to propagate distributed traces.
// Always generates a fresh request_id per request.
func Tracing() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = uuid.New().String()
		}
		requestID := uuid.New().String()

		c.Set(KeyTraceID, traceID)
		c.Set(KeyRequestID, requestID)
		c.Header("X-Trace-ID", traceID)
		c.Header("X-Request-ID", requestID)
		c.Next()
	}
}

func GetTraceID(c *gin.Context) string {
	return getString(c, KeyTraceID)
}

func GetRequestID(c *gin.Context) string {
	return getString(c, KeyRequestID)
}

func GetUserID(c *gin.Context) string {
	return getString(c, KeyUserID)
}

func SetUserID(c *gin.Context, userID string) {
	c.Set(KeyUserID, userID)
}

func SetRole(c *gin.Context, role string) {
	c.Set(KeyRole, role)
}

func GetRole(c *gin.Context) string {
	return getString(c, KeyRole)
}

func SetSessionUUID(c *gin.Context, sessionUUID string) {
	c.Set(KeySessionUUID, sessionUUID)
}

func GetSessionUUID(c *gin.Context) string {
	return getString(c, KeySessionUUID)
}

func getString(c *gin.Context, key string) string {
	if v, ok := c.Get(key); ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}
