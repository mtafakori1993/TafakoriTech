package audit

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	ResultSuccess = "success"
	ResultFailure = "failure"

	EventOTPRequest            = "auth.otp.request"
	EventOTPVerify             = "auth.otp.verify"
	EventLoginSuccess          = "auth.login.success"
	EventLoginFailed           = "auth.login.failed"
	EventLogout                = "auth.logout"
	EventTokenRefresh          = "auth.token.refresh"
	EventTokenRevoke           = "auth.token.revoke"
	EventRefreshSuccess        = "auth.token.refresh.success"
	EventTokenReuseDetected    = "security.refresh_token.reuse_detected"
	EventSessionCreate         = "auth.session.create"
	EventSessionRevoke         = "auth.session.revoke"
	EventSessionList           = "auth.session.list"
	EventSessionLogoutAll      = "auth.session.logout_all"
	EventRateLimitHit          = "security.rate_limit.hit"
	EventSuspiciousActivity    = "security.suspicious_activity"
	EventAccountLocked         = "security.account.locked"
	EventAccountUnlocked       = "security.account.unlocked"
	EventAccountAutoLocked     = "security.account.auto_locked"

	// MFA / TOTP
	EventMFASetupStarted   = "mfa.setup.started"
	EventMFAEnabled        = "mfa.enabled"
	EventMFADisabled       = "mfa.disabled"
	EventMFAChallengeOK    = "mfa.challenge.success"
	EventMFAChallengeFail  = "mfa.challenge.failed"
	EventMFABypassDevice   = "mfa.bypassed.trusted_device"
	EventRecoveryCodeUsed  = "recovery_code.used"
	EventRecoveryRegen     = "recovery_code.regenerated"

	// Devices
	EventDeviceTrusted   = "device.trusted"
	EventDeviceUntrusted = "device.untrusted"
	EventLoginNewDevice  = "login.new_device"

	// Email
	EventEmailSent   = "email.notification.sent"
	EventEmailFailed = "email.notification.failed"
)

type Actor struct {
	UserID string `json:"user_id,omitempty"`
	Type   string `json:"type"`
}

type EventContext struct {
	IP        string `json:"ip,omitempty"`
	UserAgent string `json:"user_agent,omitempty"`
	DeviceID  string `json:"device_id,omitempty"`
}

type Entry struct {
	Timestamp string                 `json:"timestamp"`
	LogType   string                 `json:"log_type"`
	Event     string                 `json:"event"`
	Result    string                 `json:"result"`
	Actor     Actor                  `json:"actor"`
	Context   EventContext           `json:"context"`
	TraceID   string                 `json:"trace_id,omitempty"`
	RequestID string                 `json:"request_id,omitempty"`
	Service   string                 `json:"service"`
	Meta      map[string]interface{} `json:"meta,omitempty"`
}

type Logger struct {
	service string
	ch      chan Entry
	wg      sync.WaitGroup
	enc     *json.Encoder
	mu      sync.Mutex
}

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

func (l *Logger) Shutdown() {
	close(l.ch)
	l.wg.Wait()
}

func (l *Logger) emit(event, result string, actor Actor, ctx EventContext, traceID, requestID string, meta map[string]interface{}) {
	entry := Entry{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		LogType:   "audit",
		Event:     event,
		Result:    result,
		Actor:     actor,
		Context:   ctx,
		TraceID:   traceID,
		RequestID: requestID,
		Service:   l.service,
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

// ── OTP ──────────────────────────────────────────────────────────────────────

func (l *Logger) OTPRequested(identifier, ip, userAgent, traceID, requestID string) {
	l.emit(EventOTPRequest, ResultSuccess,
		Actor{Type: "anonymous"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{"identifier_type": classifyIdentifier(identifier)},
	)
}

func (l *Logger) OTPVerified(userID, ip, userAgent, traceID, requestID string) {
	l.emit(EventOTPVerify, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID, nil,
	)
}

func (l *Logger) OTPFailed(identifier, ip, userAgent, traceID, requestID string) {
	l.emit(EventOTPVerify, ResultFailure,
		Actor{Type: "anonymous"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{"identifier_type": classifyIdentifier(identifier)},
	)
}

// ── Auth / Tokens ─────────────────────────────────────────────────────────────

func (l *Logger) TokenIssued(userID, ip, userAgent, traceID, requestID string) {
	l.emit(EventLoginSuccess, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID, nil,
	)
}

func (l *Logger) SessionCreated(userID, sessionUUID, ip, userAgent, traceID, requestID string) {
	l.emit(EventSessionCreate, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{"session_uuid": sessionUUID},
	)
}

func (l *Logger) TokenRefreshed(userID, sessionUUID, oldTokenID, newTokenID, ip, userAgent, traceID, requestID string) {
	l.emit(EventRefreshSuccess, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{
			"session_uuid": sessionUUID,
			"old_jti":      oldTokenID,
			"new_jti":      newTokenID,
		},
	)
}

func (l *Logger) TokenReuseDetected(userID string, sessionID int64, tokenID, ip, userAgent, traceID, requestID string) {
	l.emit(EventTokenReuseDetected, ResultFailure,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{
			"token_id":   tokenID,
			"session_id": sessionID,
		},
	)
}

func (l *Logger) Logout(userID, sessionUUID, ip, userAgent, traceID, requestID string) {
	l.emit(EventLogout, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{"session_uuid": sessionUUID},
	)
}

// ── Session management ────────────────────────────────────────────────────────

func (l *Logger) SessionRevoked(userID, sessionUUID, ip, userAgent, traceID, requestID string) {
	l.emit(EventSessionRevoke, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{"session_uuid": sessionUUID},
	)
}

func (l *Logger) SessionsLoggedOut(userID string, count int, ip, userAgent, traceID, requestID string) {
	l.emit(EventSessionLogoutAll, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{"revoked_count": count},
	)
}

func (l *Logger) SessionsListed(userID string, count int, traceID, requestID string) {
	l.emit(EventSessionList, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{},
		traceID, requestID,
		map[string]interface{}{"session_count": count},
	)
}

// ── Account locking ───────────────────────────────────────────────────────────

func (l *Logger) AccountLocked(targetUserID, actorUserID, reason, traceID, requestID string) {
	l.emit(EventAccountLocked, ResultSuccess,
		Actor{UserID: actorUserID, Type: "admin"},
		EventContext{},
		traceID, requestID,
		map[string]interface{}{"target_user_id": targetUserID, "reason": reason},
	)
}

func (l *Logger) AccountUnlocked(targetUserID, actorUserID, traceID, requestID string) {
	l.emit(EventAccountUnlocked, ResultSuccess,
		Actor{UserID: actorUserID, Type: "admin"},
		EventContext{},
		traceID, requestID,
		map[string]interface{}{"target_user_id": targetUserID},
	)
}

func (l *Logger) AccountAutoLocked(userID string, failCount int64, ip, traceID, requestID string) {
	l.emit(EventAccountAutoLocked, ResultSuccess,
		Actor{UserID: userID, Type: "system"},
		EventContext{IP: ip},
		traceID, requestID,
		map[string]interface{}{"fail_count": failCount},
	)
}

// ── Rate limiting ─────────────────────────────────────────────────────────────

func (l *Logger) RateLimitHit(limitType, identifier, endpoint, ip, traceID, requestID string, limit int, window string) {
	l.emit(EventRateLimitHit, ResultFailure,
		Actor{Type: "anonymous"},
		EventContext{IP: ip},
		traceID, requestID,
		map[string]interface{}{
			"limit_type": limitType,
			"identifier": identifier,
			"endpoint":   endpoint,
			"limit":      limit,
			"window":     window,
		},
	)
}

// ── MFA / TOTP ────────────────────────────────────────────────────────────────

func (l *Logger) MFASetupStarted(userID, ip, userAgent, traceID, requestID string) {
	l.emit(EventMFASetupStarted, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID, nil,
	)
}

func (l *Logger) MFAEnabled(userID, ip, userAgent, traceID, requestID string) {
	l.emit(EventMFAEnabled, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID, nil,
	)
}

func (l *Logger) MFADisabled(userID, ip, userAgent, traceID, requestID string, usedRecovery bool) {
	l.emit(EventMFADisabled, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent},
		traceID, requestID,
		map[string]interface{}{"used_recovery": usedRecovery},
	)
}

func (l *Logger) MFAChallengeSucceeded(userID, deviceID, ip, userAgent, traceID, requestID string, usedRecovery bool) {
	l.emit(EventMFAChallengeOK, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent, DeviceID: deviceID},
		traceID, requestID,
		map[string]interface{}{"used_recovery": usedRecovery},
	)
}

func (l *Logger) MFAChallengeFailed(userID, deviceID, ip, userAgent, reason, traceID, requestID string) {
	l.emit(EventMFAChallengeFail, ResultFailure,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent, DeviceID: deviceID},
		traceID, requestID,
		map[string]interface{}{"reason": reason},
	)
}

func (l *Logger) MFABypassedTrustedDevice(userID, deviceID, ip, userAgent, traceID, requestID string) {
	l.emit(EventMFABypassDevice, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent, DeviceID: deviceID},
		traceID, requestID, nil,
	)
}

func (l *Logger) RecoveryCodeUsed(userID, ip, traceID, requestID string) {
	l.emit(EventRecoveryCodeUsed, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip},
		traceID, requestID, nil,
	)
}

func (l *Logger) RecoveryCodesRegenerated(userID, traceID, requestID string) {
	l.emit(EventRecoveryRegen, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{},
		traceID, requestID, nil,
	)
}

// ── Devices ───────────────────────────────────────────────────────────────────

func (l *Logger) DeviceTrusted(userID, deviceID, ip, userAgent, traceID, requestID string) {
	l.emit(EventDeviceTrusted, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent, DeviceID: deviceID},
		traceID, requestID, nil,
	)
}

func (l *Logger) DeviceUntrusted(userID, deviceID, traceID, requestID string) {
	l.emit(EventDeviceUntrusted, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{DeviceID: deviceID},
		traceID, requestID, nil,
	)
}

func (l *Logger) LoginNewDevice(userID, deviceID, ip, userAgent, traceID, requestID string, meta map[string]interface{}) {
	l.emit(EventLoginNewDevice, ResultSuccess,
		Actor{UserID: userID, Type: "user"},
		EventContext{IP: ip, UserAgent: userAgent, DeviceID: deviceID},
		traceID, requestID, meta,
	)
}

// ── Email ─────────────────────────────────────────────────────────────────────

func (l *Logger) EmailSent(userID, template, traceID, requestID string) {
	l.emit(EventEmailSent, ResultSuccess,
		Actor{UserID: userID, Type: "system"},
		EventContext{},
		traceID, requestID,
		map[string]interface{}{"template": template},
	)
}

func (l *Logger) EmailFailed(userID, template, reason, traceID, requestID string) {
	l.emit(EventEmailFailed, ResultFailure,
		Actor{UserID: userID, Type: "system"},
		EventContext{},
		traceID, requestID,
		map[string]interface{}{"template": template, "reason": reason},
	)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func classifyIdentifier(id string) string {
	if strings.Contains(id, "@") {
		return "email"
	}
	return "phone"
}

func UserIDStr(id int64) string {
	return fmt.Sprintf("%d", id)
}
