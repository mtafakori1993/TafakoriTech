package repository

import (
	"context"

	"github.com/mtafakori/persona/internal/permission/domain"
	"github.com/mtafakori/persona/shared/pagination"
)

// UpdateFields holds optional fields for a partial permission update.
type UpdateFields struct {
	Name        *string
	Description *string
}

// Repository defines the persistence contract for permission entities.
type Repository interface {
	Create(ctx context.Context, p *domain.Permission) (*domain.Permission, error)
	List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.Permission], error)
	GetByID(ctx context.Context, id string) (*domain.Permission, error)
	Update(ctx context.Context, id string, fields UpdateFields) (*domain.Permission, error)
	Delete(ctx context.Context, id string) error
}
