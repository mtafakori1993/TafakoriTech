package middleware

import (
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/mtafakori/shared/middleware/ctxkey"
)

const headerRequestID = "X-Request-ID"

// RequestID reads X-Request-ID from the incoming header; generates a UUID v4 if absent.
// The ID is stored in the gin context and echoed back in the response header so the
// caller can correlate logs with a specific request.
func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.GetHeader(headerRequestID)
		if id == "" {
			id = uuid.New().String()
		}
		c.Set(ctxkey.RequestIDKey.String(), id)
		c.Header(headerRequestID, id)
		c.Next()
	}
}
