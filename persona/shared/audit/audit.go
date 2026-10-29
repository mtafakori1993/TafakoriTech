// Package audit emits structured IAM audit events to stdout for ELK ingestion.
package audit

import (
	"encoding/json"
	"os"
	"sync"
	"time"
)

const (
	ResultSuccess = "success"
	ResultFailure = "failure"

	EventUserCreated   = "user.created"
	EventUserUpdated   = "user.updated"
	EventUserDeleted   = "user.deleted"
	EventUserStatusSet = "user.status_changed"

	EventRoleCreated = "role.created"
	EventRoleUpdated = "role.updated"
	EventRoleDeleted = "role.deleted"

	EventPermissionCreated = "permission.created"
	EventPermissionUpdated = "permission.updated"
	EventPermissionDeleted = "permission.deleted"

	EventRoleAssignedToUser    = "role.assigned_to_user"
	EventRoleRevokedFromUser   = "role.revoked_from_user"
	EventPermissionAssignedToRole  = "permission.assigned_to_role"
	EventPermissionRevokedFromRole = "permission.revoked_from_role"
)

// Entry is a single IAM audit log line.
type Entry struct {
	Timestamp string                 `json:"timestamp"`
	LogType   string                 `json:"log_type"`
	Service   string                 `json:"service"`
	Event     string                 `json:"event"`
	Result    string                 `json:"result"`
	Actor     string                 `json:"actor,omitempty"`
	UserID    string                 `json:"user_id,omitempty"`
	TraceID   string                 `json:"trace_id,omitempty"`
	RequestID string                 `json:"request_id,omitempty"`
	Meta      map[string]interface{} `json:"meta,omitempty"`
}

// Logger is a non-blocking, buffered IAM audit event emitter.
type Logger struct {
	service string
	ch      chan Entry
	wg      sync.WaitGroup
	enc     *json.Encoder
	mu      sync.Mutex
}

// New creates an audit Logger that drains events asynchronously.
func New(serviceName string, bufferSize int) *Logger {
	l := &Logger{
		service: serviceName,
		ch:      make(chan Entry, bufferSize),
		enc:     json.NewEncoder(os.Stdout),
	}
	l.wg.Add(1)
	go l.drain()
	return l
}

func (l *Logger) drain() {
	defer l.wg.Done()
	for entry := range l.ch {
		l.mu.Lock()
		_ = l.enc.Encode(entry)
		l.mu.Unlock()
	}
}

// Shutdown flushes all pending events. Call on service shutdown.
func (l *Logger) Shutdown() {
	close(l.ch)
	l.wg.Wait()
}

func (l *Logger) emit(event, result, actor, userID, traceID, requestID string, meta map[string]interface{}) {
	entry := Entry{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		LogType:   "audit",
		Service:   l.service,
		Event:     event,
		Result:    result,
		Actor:     actor,
		UserID:    userID,
		TraceID:   traceID,
		RequestID: requestID,
		Meta:      meta,
	}
	select {
	case l.ch <- entry:
	default:
		l.mu.Lock()
		_ = l.enc.Encode(entry)
		l.mu.Unlock()
	}
}

// ── User events ──────────────────────────────────────────────────────────────

func (l *Logger) UserCreated(userID, actor, traceID, requestID string) {
	l.emit(EventUserCreated, ResultSuccess, actor, userID, traceID, requestID, nil)
}

func (l *Logger) UserUpdated(userID, actor, traceID, requestID string) {
	l.emit(EventUserUpdated, ResultSuccess, actor, userID, traceID, requestID, nil)
}

func (l *Logger) UserDeleted(userID, actor, traceID, requestID string) {
	l.emit(EventUserDeleted, ResultSuccess, actor, userID, traceID, requestID, nil)
}

// ── Role events ──────────────────────────────────────────────────────────────

func (l *Logger) RoleCreated(roleID, roleName, actor, traceID, requestID string) {
	l.emit(EventRoleCreated, ResultSuccess, actor, "", traceID, requestID,
		map[string]interface{}{"role_id": roleID, "role_name": roleName})
}

func (l *Logger) RoleUpdated(roleID, actor, traceID, requestID string) {
	l.emit(EventRoleUpdated, ResultSuccess, actor, "", traceID, requestID,
		map[string]interface{}{"role_id": roleID})
}

func (l *Logger) RoleDeleted(roleID, actor, traceID, requestID string) {
	l.emit(EventRoleDeleted, ResultSuccess, actor, "", traceID, requestID,
		map[string]interface{}{"role_id": roleID})
}

// ── Permission events ────────────────────────────────────────────────────────

func (l *Logger) PermissionCreated(permID, permName, actor, traceID, requestID string) {
	l.emit(EventPermissionCreated, ResultSuccess, actor, "", traceID, requestID,
		map[string]interface{}{"permission_id": permID, "permission_name": permName})
}

func (l *Logger) PermissionUpdated(permID, actor, traceID, requestID string) {
	l.emit(EventPermissionUpdated, ResultSuccess, actor, "", traceID, requestID,
		map[string]interface{}{"permission_id": permID})
}

func (l *Logger) PermissionDeleted(permID, actor, traceID, requestID string) {
	l.emit(EventPermissionDeleted, ResultSuccess, actor, "", traceID, requestID,
		map[string]interface{}{"permission_id": permID})
}

// ── RBAC assignment events ───────────────────────────────────────────────────

func (l *Logger) RoleAssignedToUser(userID, roleID, actor, traceID, requestID string) {
	l.emit(EventRoleAssignedToUser, ResultSuccess, actor, userID, traceID, requestID,
		map[string]interface{}{"role_id": roleID})
}

func (l *Logger) RoleRevokedFromUser(userID, roleID, actor, traceID, requestID string) {
	l.emit(EventRoleRevokedFromUser, ResultSuccess, actor, userID, traceID, requestID,
		map[string]interface{}{"role_id": roleID})
}

func (l *Logger) PermissionAssignedToRole(roleID, permID, actor, traceID, requestID string) {
	l.emit(EventPermissionAssignedToRole, ResultSuccess, actor, "", traceID, requestID,
		map[string]interface{}{"role_id": roleID, "permission_id": permID})
}

func (l *Logger) PermissionRevokedFromRole(roleID, permID, actor, traceID, requestID string) {
	l.emit(EventPermissionRevokedFromRole, ResultSuccess, actor, "", traceID, requestID,
		map[string]interface{}{"role_id": roleID, "permission_id": permID})
}
