package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/mtafakori/pulse/internal/model"
)

type Store interface {
	Create(ctx context.Context, n *model.Notification) error
	Update(ctx context.Context, n *model.Notification) error
	GetByID(ctx context.Context, id string) (*model.Notification, error)
	ListPendingOlderThan(ctx context.Context, age time.Duration) ([]*model.Notification, error)
}

type pgStore struct {
	pool *pgxpool.Pool
}

func New(pool *pgxpool.Pool) Store {
	return &pgStore{pool: pool}
}

func (s *pgStore) Create(ctx context.Context, n *model.Notification) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO notifications
			(id, channel, recipient, subject, body, status, attempts, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, NOW(), NOW())`,
		n.ID, n.Channel, n.Recipient, n.Subject, n.Body, n.Status, n.Attempts,
	)
	return err
}

func (s *pgStore) Update(ctx context.Context, n *model.Notification) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE notifications
		SET status = $1, attempts = $2, error_msg = $3, sent_at = $4, updated_at = NOW()
		WHERE id = $5`,
		n.Status, n.Attempts, n.ErrorMsg, n.SentAt, n.ID,
	)
	return err
}

func (s *pgStore) GetByID(ctx context.Context, id string) (*model.Notification, error) {
	row := s.pool.QueryRow(ctx, `
		SELECT id, channel, recipient, subject, body, status, attempts, error_msg, sent_at, created_at, updated_at
		FROM notifications WHERE id = $1`, id)
	return scanNotification(row)
}

func (s *pgStore) ListPendingOlderThan(ctx context.Context, age time.Duration) ([]*model.Notification, error) {
	cutoff := time.Now().Add(-age)
	rows, err := s.pool.Query(ctx, `
		SELECT id, channel, recipient, subject, body, status, attempts, error_msg, sent_at, created_at, updated_at
		FROM notifications
		WHERE status = 'PENDING' AND created_at < $1`, cutoff)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []*model.Notification
	for rows.Next() {
		n, err := scanNotification(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

func scanNotification(row pgx.Row) (*model.Notification, error) {
	n := &model.Notification{}
	err := row.Scan(
		&n.ID, &n.Channel, &n.Recipient, &n.Subject, &n.Body,
		&n.Status, &n.Attempts, &n.ErrorMsg, &n.SentAt,
		&n.CreatedAt, &n.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}
	return n, nil
}
