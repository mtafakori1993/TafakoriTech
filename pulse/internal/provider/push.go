package provider

import (
	"context"

	"github.com/mtafakori/pulse/internal/logger"
	"github.com/mtafakori/pulse/internal/model"
)

type PushProvider struct{}

func NewPushProvider() *PushProvider { return &PushProvider{} }

func (p *PushProvider) Send(_ context.Context, n *model.Notification) error {
	logger.Log.Info().
		Str("event", "push_mock_delivery").
		Str("notification_id", n.ID).
		Str("recipient", n.Recipient).
		Str("body", n.Body).
		Msg("push notification delivered (mock)")
	return nil
}
