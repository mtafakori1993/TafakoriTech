package provider

import (
	"context"

	"github.com/mtafakori/pulse/internal/model"
)

type Provider interface {
	Send(ctx context.Context, n *model.Notification) error
}
