package handler

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/sentinel/internal/audit"
	"github.com/mtafakori/sentinel/internal/mfa"
	"github.com/mtafakori/sentinel/internal/middleware"
)

type mfaCodeReq struct {
	Code string `json:"code" binding:"required"`
}

// MFASetup begins enrollment: generates a secret + QR, moves user to pending.
// POST /mfa/setup  (auth required)
func (h *Handler) MFASetup(c *gin.Context) {
	userID, ok := getUserIDInt64(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}
	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)

	enr, err := h.mfa.Setup(c.Request.Context(), userID)
	if err != nil {
		switch {
		case errors.Is(err, mfa.ErrMFAAlreadyEnabled):
			c.JSON(http.StatusConflict, gin.H{"error": "mfa already enabled"})
		case errors.Is(err, mfa.ErrUserNotFound):
			c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		default:
			c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		}
		return
	}

	h.audit.MFASetupStarted(audit.UserIDStr(userID), c.ClientIP(), c.Request.UserAgent(), traceID, requestID)
	c.JSON(http.StatusOK, gin.H{
		"otpauth_url":   enr.OTPAuthURL,
		"qr_png_base64": enr.QRPNGBase64,
		"secret":        enr.Secret, // shown once, for manual entry
	})
}

// MFAVerifySetup confirms the first code and returns recovery codes (once).
// POST /mfa/verify-setup  (auth required)
func (h *Handler) MFAVerifySetup(c *gin.Context) {
	userID, ok := getUserIDInt64(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}
	var req mfaCodeReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	ip := c.ClientIP()
	ua := c.Request.UserAgent()
	userIDStr := audit.UserIDStr(userID)

	codes, err := h.mfa.VerifySetup(c.Request.Context(), userID, req.Code)
	if err != nil {
		switch {
		case errors.Is(err, mfa.ErrMFANotPending):
			c.JSON(http.StatusConflict, gin.H{"error": "mfa setup not started"})
		case errors.Is(err, mfa.ErrInvalidTOTP), errors.Is(err, mfa.ErrTOTPReplay):
			h.audit.MFAChallengeFailed(userIDStr, "", ip, ua, "invalid_totp", traceID, requestID)
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid code"})
		default:
			c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		}
		return
	}

	h.audit.MFAEnabled(userIDStr, ip, ua, traceID, requestID)
	// TODO(step6): enqueue "mfa_enabled" security email.
	c.JSON(http.StatusOK, gin.H{
		"message":        "mfa enabled",
		"recovery_codes": codes, // shown ONCE
	})
}

// MFADisable turns MFA off after verifying a current factor.
// POST /mfa/disable  (auth required)
func (h *Handler) MFADisable(c *gin.Context) {
	userID, ok := getUserIDInt64(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}
	var req mfaCodeReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)
	ip := c.ClientIP()
	ua := c.Request.UserAgent()
	userIDStr := audit.UserIDStr(userID)

	usedRecovery, err := h.mfa.Disable(c.Request.Context(), userID, req.Code)
	if err != nil {
		switch {
		case errors.Is(err, mfa.ErrMFANotEnabled):
			c.JSON(http.StatusConflict, gin.H{"error": "mfa not enabled"})
		case errors.Is(err, mfa.ErrInvalidTOTP), errors.Is(err, mfa.ErrTOTPReplay):
			h.audit.MFAChallengeFailed(userIDStr, "", ip, ua, "invalid_totp", traceID, requestID)
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid code"})
		default:
			c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		}
		return
	}

	h.audit.MFADisabled(userIDStr, ip, ua, traceID, requestID, usedRecovery)
	// TODO(step6): enqueue "mfa_disabled" security email.
	c.JSON(http.StatusOK, gin.H{"message": "mfa disabled"})
}

// GenRecoveryCodes regenerates recovery codes, invalidating the previous set.
// POST /mfa/recovery-codes/generate  (auth required)
func (h *Handler) GenRecoveryCodes(c *gin.Context) {
	userID, ok := getUserIDInt64(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}
	traceID := middleware.GetTraceID(c)
	requestID := middleware.GetRequestID(c)

	codes, err := h.mfa.RegenerateRecoveryCodes(c.Request.Context(), userID)
	if err != nil {
		if errors.Is(err, mfa.ErrMFANotEnabled) {
			c.JSON(http.StatusConflict, gin.H{"error": "mfa not enabled"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
		return
	}

	h.audit.RecoveryCodesRegenerated(audit.UserIDStr(userID), traceID, requestID)
	c.JSON(http.StatusOK, gin.H{"recovery_codes": codes})
}
