package service

import (
	"context"
	"fmt"

	"github.com/mtafakori/persona/internal/access/repository/postgres"
	"github.com/mtafakori/persona/shared/apperror"
)

// Service provides the authorization context lookup used by internal callers
// (primarily Sentinel during JWT issuance).
type Service struct {
	repo *postgres.Repository
}

// New returns a new access Service.
func New(repo *postgres.Repository) *Service {
	return &Service{repo: repo}
}

// GetUserContext returns the complete authorization snapshot for a user.
func (s *Service) GetUserContext(ctx context.Context, userID string) (*postgres.UserContext, error) {
	if userID == "" {
		return nil, fmt.Errorf("%w: user_id is required", apperror.ErrInvalidInput)
	}
	return s.repo.GetUserContext(ctx, userID)
}
