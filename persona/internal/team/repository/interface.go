package repository

import (
	"context"

	"github.com/mtafakori/persona/internal/team/domain"
	user_domain "github.com/mtafakori/persona/internal/user/domain"
	"github.com/mtafakori/persona/shared/pagination"
)

// UpdateFields holds optional fields for a partial team update.
type UpdateFields struct {
	Name        *string
	Description *string
}

// Repository defines the persistence contract for team entities.
type Repository interface {
	Create(ctx context.Context, t *domain.Team) (*domain.Team, error)
	List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.Team], error)
	GetByID(ctx context.Context, id string) (*domain.Team, error)
	Update(ctx context.Context, id string, fields UpdateFields) (*domain.Team, error)
	Delete(ctx context.Context, id string) error
	AddUser(ctx context.Context, teamID, userID string) error
	RemoveUser(ctx context.Context, teamID, userID string) error
	ListUsers(ctx context.Context, teamID string) ([]user_domain.User, error)
}
