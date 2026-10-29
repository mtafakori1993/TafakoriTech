package middleware

import (
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/mtafakori/shared/middleware/ctxkey"
)

const headerTraceID = "X-Trace-ID"

// TraceID reads X-Trace-ID from the incoming header; generates a UUID v4 if absent.
// The ID is stored in the gin context and echoed back in X-Trace-ID so downstream
// services can propagate it in their own outgoing requests, enabling end-to-end
// correlation across the entire call chain.
//
// Services making outbound HTTP calls must forward the trace ID:
//
//	traceID, _ := c.Get(ctxkey.TraceIDKey.String())
//	req.Header.Set("X-Trace-ID", traceID.(string))
func TraceID() gin.HandlerFunc {
	return func(c *gin.Context) {
		id := c.GetHeader(headerTraceID)
		if id == "" {
			id = uuid.New().String()
		}
		c.Set(ctxkey.TraceIDKey.String(), id)
		c.Header(headerTraceID, id)
		c.Next()
	}
}
