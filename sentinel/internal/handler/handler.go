package handler

import (
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gin-gonic/gin/binding"
	"github.com/mtafakori/sentinel/internal/audit"
	"github.com/mtafakori/sentinel/internal/metrics"
	"github.com/mtafakori/sentinel/internal/mfa"
	"github.com/mtafakori/sentinel/internal/middleware"
	"github.com/mtafakori/sentinel/internal/service"
)

type Handler struct {
	svc   *service.Service
	mfa   *mfa.Service
	audit *audit.Logger
}

func New(svc *service.Service, mfaSvc *mfa.Service, auditLog *audit.Logger) *Handler {
	return &Handler{svc: svc, mfa: mfaSvc, audit: auditLog}
}

// ── Request bodies ────────────────────────────────────────────────────────────

type otpRequestReq struct {
	Identifier string `json:"identifier" binding:"required"`
}

type otpVerifyReq struct {
	Identifier string `json:"identifier" binding:"required"`
	OTP        string `json:"otp" binding:"required"`
}

type mfaLoginReq struct {
	MFAToken string `json:"mfa_token" binding:"required"`
	Code     string `json:"code" binding:"required"`
}

type refreshReq struct {
	RefreshKey string `json:"refresh_key" binding:"required"`
}

type logoutReq struct {
	RefreshKey string `json:"refresh_key" binding:"required"`
}

type lockAccountReq struct {
	UserID      int64   `json:"user_id" binding:"required"`
	Reason      string  `json:"reason"`
	LockedUntil *string `json:"locked_until"` // RFC3339; omit for permanent
}

type unlockAccountReq struct {
	UserID int64 `json:"user_id" binding:"required"`
}

// ── OTP / Auth ────────────────────────────────────────────────────────────────

func (h *Handler) OTPRequest(c *gin.Context) {
	var req otpRequestReq
	if err := c.ShouldBindBodyWith(&req, binding.JSON); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)

	if err := h.svc.RequestOTP(c.Request.Context(), req.Identifier); err != nil {
		switch {
		case errors.Is(err, service.ErrUserNotFound):
			c.JSON(http.StatusUnauthorized, gin.H{"error": "user not registered"})
		case errors.Is(err, service.ErrUserInactive):
			c.JSON(http.StatusForbidden, gin.H{"error": "user account is inactive"})
		case errors.Is(err, service.ErrAccountLocked):
			c.JSON(http.StatusForbidden, gin.H{"error": "account is locked"})
		default:
			c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		}
		return
	}

	h.audit.OTPRequested(req.Identifier, c.ClientIP(), c.Request.UserAgent(), traceID, requestID)
	metrics.RecordAuthEvent(audit.EventOTPRequest, audit.ResultSuccess)
	c.JSON(http.StatusOK, gin.H{"message": "OTP sent"})
}

func (h *Handler) OTPVerify(c *gin.Context) {
	var req otpVerifyReq
	if err := c.ShouldBindBodyWith(&req, binding.JSON); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	ip := c.ClientIP()
	ua := c.Request.UserAgent()

	result, err := h.svc.VerifyOTP(c.Request.Context(), req.Identifier, req.OTP, ip, ua)
	if err != nil {
		var autoLock *service.AutoLockedError
		var mfaReq *service.MFARequiredError
		switch {
		case errors.As(err, &mfaReq):
			// OTP was correct, but MFA is enabled. Issue a short-lived challenge
			// token; the client must complete login via /mfa/login.
			mfaToken, signErr := h.svc.SignMFAChallenge(mfaReq.UserID)
			if signErr != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
				return
			}
			userIDStr := audit.UserIDStr(mfaReq.UserID)
			h.audit.OTPVerified(userIDStr, ip, ua, traceID, requestID)
			c.JSON(http.StatusOK, gin.H{"mfa_required": true, "mfa_token": mfaToken})
		case errors.As(err, &autoLock):
			userIDStr := audit.UserIDStr(autoLock.UserID)
			h.audit.AccountAutoLocked(userIDStr, autoLock.FailCount, ip, traceID, requestID)
			metrics.RecordAccountLock("auto")
			metrics.RecordAuthEvent(audit.EventOTPVerify, audit.ResultFailure)
			c.JSON(http.StatusForbidden, gin.H{"error": "account locked due to too many failed attempts"})
		case errors.Is(err, service.ErrAccountLocked):
			c.JSON(http.StatusForbidden, gin.H{"error": "account is locked"})
		case errors.Is(err, service.ErrInvalidOTP):
			h.audit.OTPFailed(req.Identifier, ip, ua, traceID, requestID)
			metrics.RecordAuthEvent(audit.EventOTPVerify, audit.ResultFailure)
			metrics.RecordOTPAbuse(classifyIdentifier(req.Identifier))
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired OTP"})
		default:
			c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		}
		return
	}

	userIDStr := audit.UserIDStr(result.UserID)
	middleware.SetUserID(c, userIDStr)

	h.audit.OTPVerified(userIDStr, ip, ua, traceID, requestID)
	h.audit.TokenIssued(userIDStr, ip, ua, traceID, requestID)
	h.audit.SessionCreated(userIDStr, result.SessionUUID, ip, ua, traceID, requestID)
	metrics.RecordAuthEvent(audit.EventOTPVerify, audit.ResultSuccess)

	c.JSON(http.StatusOK, result.TokenPair)
}

// MFALogin completes a login that requires a second factor. It consumes the
// mfa_challenge token issued by OTPVerify plus a TOTP or recovery code, and on
// success issues the real token pair.
func (h *Handler) MFALogin(c *gin.Context) {
	var req mfaLoginReq
	if err := c.ShouldBindBodyWith(&req, binding.JSON); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	ip := c.ClientIP()
	ua := c.Request.UserAgent()

	userID, err := h.svc.ParseMFAChallenge(req.MFAToken)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired mfa session"})
		return
	}
	userIDStr := audit.UserIDStr(userID)

	usedRecovery, err := h.mfa.VerifyChallenge(c.Request.Context(), userID, req.Code)
	if err != nil {
		switch {
		case errors.Is(err, mfa.ErrMFANotEnabled):
			c.JSON(http.StatusConflict, gin.H{"error": "mfa not enabled"})
		case errors.Is(err, mfa.ErrInvalidTOTP), errors.Is(err, mfa.ErrTOTPReplay):
			h.audit.MFAChallengeFailed(userIDStr, "", ip, ua, "invalid_totp", traceID, requestID)
			metrics.RecordAuthEvent(audit.EventMFAChallengeFail, audit.ResultFailure)
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid code"})
		default:
			c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		}
		return
	}

	result, err := h.svc.IssueTokensByUserID(c.Request.Context(), userID, ip, ua)
	if err != nil {
		if errors.Is(err, service.ErrAccountLocked) {
			c.JSON(http.StatusForbidden, gin.H{"error": "account is locked"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	middleware.SetUserID(c, userIDStr)
	h.audit.MFAChallengeSucceeded(userIDStr, "", ip, ua, traceID, requestID, usedRecovery)
	h.audit.TokenIssued(userIDStr, ip, ua, traceID, requestID)
	h.audit.SessionCreated(userIDStr, result.SessionUUID, ip, ua, traceID, requestID)
	metrics.RecordAuthEvent(audit.EventMFAChallengeOK, audit.ResultSuccess)
	metrics.RecordAuthEvent(audit.EventOTPVerify, audit.ResultSuccess)

	c.JSON(http.StatusOK, result.TokenPair)
}

func (h *Handler) Refresh(c *gin.Context) {
	var req refreshReq
	if err := c.ShouldBindBodyWith(&req, binding.JSON); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	ip := c.ClientIP()
	ua := c.Request.UserAgent()

	result, err := h.svc.Refresh(c.Request.Context(), req.RefreshKey)
	if err != nil {
		var reuseErr *service.TokenReuseError
		if errors.As(err, &reuseErr) {
			userIDStr := audit.UserIDStr(reuseErr.UserID)
			h.audit.TokenReuseDetected(userIDStr, reuseErr.SessionID, reuseErr.TokenID, ip, ua, traceID, requestID)
			metrics.RecordTokenReuse()
			metrics.RecordSessionRevocation("reuse_detected")
			metrics.RecordAuthEvent(audit.EventTokenReuseDetected, audit.ResultFailure)
			c.JSON(http.StatusUnauthorized, gin.H{"error": "session revoked"})
			return
		}
		if errors.Is(err, service.ErrAccountLocked) {
			c.JSON(http.StatusForbidden, gin.H{"error": "account is locked"})
			return
		}
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired refresh key"})
		return
	}

	userIDStr := audit.UserIDStr(result.UserID)
	middleware.SetUserID(c, userIDStr)

	h.audit.TokenRefreshed(userIDStr, result.SessionUUID, result.OldTokenID, result.NewTokenID, ip, ua, traceID, requestID)
	metrics.RecordAuthEvent(audit.EventRefreshSuccess, audit.ResultSuccess)

	c.JSON(http.StatusOK, result.TokenPair)
}

func (h *Handler) Logout(c *gin.Context) {
	var req logoutReq
	if err := c.ShouldBindBodyWith(&req, binding.JSON); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	ip := c.ClientIP()
	ua := c.Request.UserAgent()

	userID, sessionUUID, _ := h.svc.Logout(c.Request.Context(), req.RefreshKey)

	userIDStr := audit.UserIDStr(userID)
	h.audit.Logout(userIDStr, sessionUUID, ip, ua, traceID, requestID)
	metrics.RecordAuthEvent(audit.EventLogout, audit.ResultSuccess)
	metrics.RecordSessionRevocation("logout")

	c.JSON(http.StatusOK, gin.H{"message": "logged out"})
}

// ── Session management (requires auth) ───────────────────────────────────────

func (h *Handler) ListSessions(c *gin.Context) {
	userID, ok := getUserIDInt64(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)

	sessions, total, err := h.svc.ListSessions(c.Request.Context(), userID, page, pageSize)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	h.audit.SessionsListed(audit.UserIDStr(userID), len(sessions), traceID, requestID)
	metrics.RecordAuthEvent(audit.EventSessionList, audit.ResultSuccess)

	c.JSON(http.StatusOK, gin.H{
		"sessions":  sessions,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

func (h *Handler) RevokeSession(c *gin.Context) {
	userID, ok := getUserIDInt64(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	sessionUUID := c.Param("session_uuid")
	if sessionUUID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "session_uuid is required"})
		return
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	ip := c.ClientIP()
	ua := c.Request.UserAgent()

	if err := h.svc.RevokeSession(c.Request.Context(), userID, sessionUUID); err != nil {
		if errors.Is(err, service.ErrSessionNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	userIDStr := audit.UserIDStr(userID)
	h.audit.SessionRevoked(userIDStr, sessionUUID, ip, ua, traceID, requestID)
	metrics.RecordSessionRevocation("admin")
	metrics.RecordAuthEvent(audit.EventSessionRevoke, audit.ResultSuccess)

	c.JSON(http.StatusOK, gin.H{"message": "session revoked"})
}

func (h *Handler) LogoutAll(c *gin.Context) {
	userID, ok := getUserIDInt64(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	ip := c.ClientIP()
	ua := c.Request.UserAgent()

	count, err := h.svc.LogoutAll(c.Request.Context(), userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	userIDStr := audit.UserIDStr(userID)
	h.audit.SessionsLoggedOut(userIDStr, count, ip, ua, traceID, requestID)
	metrics.RecordAuthEvent(audit.EventSessionLogoutAll, audit.ResultSuccess)
	if count > 0 {
		metrics.RecordSessionRevocation("logout_all")
	}

	c.JSON(http.StatusOK, gin.H{"message": "all sessions revoked", "count": count})
}

// ── Account management (admin only) ──────────────────────────────────────────

func (h *Handler) LockAccount(c *gin.Context) {
	var req lockAccountReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var lockedUntil *time.Time
	if req.LockedUntil != nil && *req.LockedUntil != "" {
		t, err := time.Parse(time.RFC3339, *req.LockedUntil)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "locked_until must be RFC3339"})
			return
		}
		lockedUntil = &t
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	actorID := middleware.GetUserID(c)

	if err := h.svc.LockAccount(c.Request.Context(), req.UserID, req.Reason, lockedUntil); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	targetStr := audit.UserIDStr(req.UserID)
	h.audit.AccountLocked(targetStr, actorID, req.Reason, traceID, requestID)
	metrics.RecordAccountLock("admin")
	metrics.RecordAuthEvent(audit.EventAccountLocked, audit.ResultSuccess)

	c.JSON(http.StatusOK, gin.H{"message": "account locked"})
}

func (h *Handler) UnlockAccount(c *gin.Context) {
	var req unlockAccountReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	actorID := middleware.GetUserID(c)

	if err := h.svc.UnlockAccount(c.Request.Context(), req.UserID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	targetStr := audit.UserIDStr(req.UserID)
	h.audit.AccountUnlocked(targetStr, actorID, traceID, requestID)
	metrics.RecordAuthEvent(audit.EventAccountUnlocked, audit.ResultSuccess)

	c.JSON(http.StatusOK, gin.H{"message": "account unlocked"})
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func getUserIDInt64(c *gin.Context) (int64, bool) {
	s := middleware.GetUserID(c)
	if s == "" {
		return 0, false
	}
	n, err := strconv.ParseInt(s, 10, 64)
	return n, err == nil
}

func classifyIdentifier(id string) string {
	for _, ch := range id {
		if ch == '@' {
			return "email"
		}
	}
	return "phone"
}
