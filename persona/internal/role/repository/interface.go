package repository

import (
	"context"

	"github.com/mtafakori/persona/internal/permission/domain"
	role_domain "github.com/mtafakori/persona/internal/role/domain"
	"github.com/mtafakori/persona/shared/pagination"
)

// UpdateFields holds optional fields for a partial role update.
type UpdateFields struct {
	Name        *string
	Description *string
}

// Repository defines the persistence contract for role entities.
type Repository interface {
	Create(ctx context.Context, r *role_domain.Role) (*role_domain.Role, error)
	List(ctx context.Context, page pagination.Page) (*pagination.Result[role_domain.Role], error)
	GetByID(ctx context.Context, id string) (*role_domain.Role, error)
	Update(ctx context.Context, id string, fields UpdateFields) (*role_domain.Role, error)
	Delete(ctx context.Context, id string) error
	AssignPermission(ctx context.Context, roleID, permissionID string) error
	RemovePermission(ctx context.Context, roleID, permissionID string) error
	ListPermissions(ctx context.Context, roleID string) ([]domain.Permission, error)
}
