package repository

import (
	"context"

	role_domain "github.com/mtafakori/persona/internal/role/domain"
	team_domain "github.com/mtafakori/persona/internal/team/domain"
	"github.com/mtafakori/persona/internal/user/domain"
	"github.com/mtafakori/persona/shared/pagination"
)

// UpdateFields holds optional fields for a partial user update.
// Only non-nil fields are applied.
type UpdateFields struct {
	FirstName *string
	LastName  *string
	Phone     *string
	AvatarURL *string
	Status    *domain.Status
}

// Repository defines the persistence contract for user entities.
type Repository interface {
	Create(ctx context.Context, u *domain.User) (*domain.User, error)
	List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.User], error)
	GetByID(ctx context.Context, id string) (*domain.User, error)
	GetByEmail(ctx context.Context, email string) (*domain.User, error)
	GetByIdentifier(ctx context.Context, identifier string) (*domain.User, error)
	Update(ctx context.Context, id string, fields UpdateFields) (*domain.User, error)
	Delete(ctx context.Context, id string) error
	AssignRole(ctx context.Context, userID, roleID string) error
	RemoveRole(ctx context.Context, userID, roleID string) error
	ListRoles(ctx context.Context, userID string) ([]role_domain.Role, error)
	ListTeams(ctx context.Context, userID string) ([]team_domain.Team, error)
}
