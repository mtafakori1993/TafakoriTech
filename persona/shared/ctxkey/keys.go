package ctxkey

// contextKey is an unexported type for context keys in this package,
// preventing collisions with keys from other packages.
type contextKey string

const (
	// RequestIDKey stores the X-Request-ID for the current request.
	RequestIDKey contextKey = "request_id"

	// TraceIDKey stores the X-Trace-ID for distributed tracing.
	TraceIDKey contextKey = "trace_id"

	// AuthUserIDKey stores the authenticated user's ID (JWT sub claim).
	AuthUserIDKey contextKey = "auth_user_id"

	// AuthRoleKey stores the authenticated user's role from the JWT.
	AuthRoleKey contextKey = "auth_role"
)
