package handler

import (
	"crypto/sha256"
	"fmt"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/mtafakori/pulse/internal/dispatcher"
	"github.com/mtafakori/pulse/internal/logger"
	"github.com/mtafakori/pulse/internal/middleware"
	"github.com/mtafakori/pulse/internal/model"
	"github.com/mtafakori/pulse/internal/store"
)

type Handler struct {
	store      store.Store
	dispatcher *dispatcher.Dispatcher
}

func New(st store.Store, d *dispatcher.Dispatcher) *Handler {
	return &Handler{store: st, dispatcher: d}
}

type submitRequest struct {
	Channel   string `json:"channel"   binding:"required"`
	Recipient string `json:"recipient" binding:"required"`
	Subject   string `json:"subject"`
	Body      string `json:"body"      binding:"required"`
}

// Submit handles POST /api/v1/notifications.
// Persists the notification immediately and returns 202 — delivery is async.
func (h *Handler) Submit(c *gin.Context) {
	var req submitRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	switch req.Channel {
	case model.ChannelEmail, model.ChannelSMS, model.ChannelPush:
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "channel must be EMAIL, SMS, or PUSH"})
		return
	}

	n := &model.Notification{
		ID:        uuid.New().String(),
		Channel:   req.Channel,
		Recipient: req.Recipient,
		Subject:   req.Subject,
		Body:      req.Body,
		Status:    model.StatusPending,
		Attempts:  0,
	}

	ctx := c.Request.Context()
	if err := h.store.Create(ctx, n); err != nil {
		logger.Log.Error().Err(err).Str("notification_id", n.ID).Msg("failed to persist notification")
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create notification"})
		return
	}

	logger.Log.Info().
		Str("event", "notification_received").
		Str("notification_id", n.ID).
		Str("channel", n.Channel).
		Str("recipient_hash", recipientHash(n.Recipient)).
		Str("trace_id", middleware.GetTraceID(c)).
		Msg("notification_received")

	if !h.dispatcher.Enqueue(n) {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "queue full, try again later"})
		return
	}

	c.JSON(http.StatusAccepted, gin.H{
		"notification_id": n.ID,
		"status":          n.Status,
	})
}

// GetStatus handles GET /api/v1/notifications/:id.
func (h *Handler) GetStatus(c *gin.Context) {
	id := c.Param("id")

	n, err := h.store.GetByID(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "notification not found"})
		return
	}

	resp := gin.H{
		"notification_id": n.ID,
		"channel":         n.Channel,
		"recipient":       n.Recipient,
		"status":          n.Status,
		"attempts":        n.Attempts,
		"created_at":      n.CreatedAt,
	}
	if n.SentAt != nil {
		resp["sent_at"] = n.SentAt
	}
	c.JSON(http.StatusOK, resp)
}

func recipientHash(r string) string {
	sum := sha256.Sum256([]byte(r))
	return fmt.Sprintf("sha256:%x", sum[:4])
}
