package dispatcher

import (
	"context"
	"crypto/sha256"
	"fmt"
	"time"

	"github.com/mtafakori/pulse/internal/logger"
	"github.com/mtafakori/pulse/internal/model"
	"github.com/mtafakori/pulse/internal/provider"
	"github.com/mtafakori/pulse/internal/store"
)

const maxAttempts = 3

var backoff = [maxAttempts]time.Duration{0, 2 * time.Second, 5 * time.Second}

type Dispatcher struct {
	queue   chan *model.Notification
	store   store.Store
	email   provider.Provider
	sms     provider.Provider
	push    provider.Provider
	workers int
}

func New(
	workerCount, queueBuffer int,
	st store.Store,
	email, sms, push provider.Provider,
) *Dispatcher {
	return &Dispatcher{
		queue:   make(chan *model.Notification, queueBuffer),
		store:   st,
		email:   email,
		sms:     sms,
		push:    push,
		workers: workerCount,
	}
}

func (d *Dispatcher) Start(ctx context.Context) {
	for i := 0; i < d.workers; i++ {
		go d.work(ctx)
	}
}

// Enqueue sends a notification to the worker pool. Returns false if the queue
// is full (caller should surface a 503).
func (d *Dispatcher) Enqueue(n *model.Notification) bool {
	select {
	case d.queue <- n:
		return true
	default:
		return false
	}
}

// Recover re-enqueues PENDING notifications that predate the service startup
// (indicates a crash before delivery). Called once at boot.
func (d *Dispatcher) Recover(ctx context.Context, age time.Duration) {
	pending, err := d.store.ListPendingOlderThan(ctx, age)
	if err != nil {
		logger.Log.Error().Err(err).Msg("dispatcher: recovery query failed")
		return
	}
	for _, n := range pending {
		if !d.Enqueue(n) {
			logger.Log.Warn().Str("notification_id", n.ID).Msg("dispatcher: recovery queue full, skipping")
		}
	}
	if len(pending) > 0 {
		logger.Log.Info().Int("count", len(pending)).Msg("dispatcher: re-enqueued stale pending notifications")
	}
}

func (d *Dispatcher) work(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case n := <-d.queue:
			d.processOne(ctx, n)
		}
	}
}

func (d *Dispatcher) processOne(ctx context.Context, n *model.Notification) {
	p := d.routeProvider(n.Channel)
	if p == nil {
		logger.Log.Error().Str("notification_id", n.ID).Str("channel", n.Channel).Msg("dispatcher: unknown channel")
		n.Status = model.StatusFailed
		n.ErrorMsg = "unknown channel: " + n.Channel
		_ = d.store.Update(ctx, n)
		return
	}

	for {
		if n.Attempts > 0 {
			sleep := backoff[min(n.Attempts, maxAttempts-1)]
			logger.Log.Warn().
				Str("event", "retry_scheduled").
				Str("notification_id", n.ID).
				Int("attempt", n.Attempts+1).
				Int64("backoff_ms", sleep.Milliseconds()).
				Msg("retry_scheduled")
			time.Sleep(sleep)
		}

		logger.Log.Info().
			Str("event", "delivery_attempt").
			Str("notification_id", n.ID).
			Str("channel", n.Channel).
			Int("attempt", n.Attempts+1).
			Msg("delivery_attempt")

		start := time.Now()
		err := p.Send(ctx, n)
		n.Attempts++

		if err == nil {
			now := time.Now()
			n.Status = model.StatusSent
			n.SentAt = &now
			_ = d.store.Update(ctx, n)

			logger.Log.Info().
				Str("event", "delivery_success").
				Str("notification_id", n.ID).
				Str("channel", n.Channel).
				Int("attempt", n.Attempts).
				Int64("duration_ms", time.Since(start).Milliseconds()).
				Msg("delivery_success")
			return
		}

		_ = d.store.Update(ctx, n)

		if n.Attempts >= maxAttempts {
			n.Status = model.StatusFailed
			n.ErrorMsg = err.Error()
			_ = d.store.Update(ctx, n)

			logger.Log.Error().
				Str("event", "delivery_failed").
				Str("notification_id", n.ID).
				Str("channel", n.Channel).
				Int("attempts", n.Attempts).
				Str("error", err.Error()).
				Msg("delivery_failed")
			return
		}
	}
}

func (d *Dispatcher) routeProvider(channel string) provider.Provider {
	switch channel {
	case model.ChannelEmail:
		return d.email
	case model.ChannelSMS:
		return d.sms
	case model.ChannelPush:
		return d.push
	}
	return nil
}

// recipientHash returns an 8-char hex prefix of the SHA-256 hash — used in
// logs to identify recipients without storing PII.
func recipientHash(r string) string {
	sum := sha256.Sum256([]byte(r))
	return fmt.Sprintf("sha256:%x", sum[:4])
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
