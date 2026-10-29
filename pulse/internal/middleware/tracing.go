package middleware

import (
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

const (
	KeyTraceID   = "trace_id"
	KeyRequestID = "request_id"
	KeyUserID    = "user_id"
	KeyRole      = "role"
)

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

func GetTraceID(c *gin.Context) string    { return getString(c, KeyTraceID) }
func GetRequestID(c *gin.Context) string  { return getString(c, KeyRequestID) }
func GetUserID(c *gin.Context) string     { return getString(c, KeyUserID) }

func getString(c *gin.Context, key string) string {
	if v, ok := c.Get(key); ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}
