// Package ctxkey defines typed context keys shared across all TafakoriTech services.
// Using a private type prevents collisions with keys from other packages.
package ctxkey

type contextKey string

func (k contextKey) String() string { return string(k) }

const (
	// RequestIDKey stores the X-Request-ID for the current HTTP request.
	// Set by the RequestID middleware; read by Logger and Recovery middleware.
	RequestIDKey contextKey = "request_id"

	// TraceIDKey stores the X-Trace-ID for distributed tracing across services.
	// Set by the TraceID middleware; must be forwarded in outgoing service calls.
	TraceIDKey contextKey = "trace_id"

	// UserIDKey stores the authenticated user's ID extracted from the JWT sub claim.
	// Populated by Auth middleware; optional — may be empty for unauthenticated paths.
	UserIDKey contextKey = "user_id"

	// TenantIDKey stores the tenant identifier for multi-tenant support.
	TenantIDKey contextKey = "tenant_id"
)
