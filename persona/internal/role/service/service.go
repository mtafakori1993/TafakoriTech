package service

import (
	"context"
	"fmt"

	permission_domain "github.com/mtafakori/persona/internal/permission/domain"
	"github.com/mtafakori/persona/internal/role/domain"
	"github.com/mtafakori/persona/internal/role/repository"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

// Service provides business logic for role management.
type Service struct {
	repo repository.Repository
}

// New returns a new role Service backed by the given repository.
func New(repo repository.Repository) *Service {
	return &Service{repo: repo}
}

// CreateInput holds the required data for creating a new role.
type CreateInput struct {
	Name        string
	Slug        string
	Description string
}

func (s *Service) Create(ctx context.Context, in CreateInput) (*domain.Role, error) {
	if in.Name == "" {
		return nil, fmt.Errorf("%w: name is required", apperror.ErrInvalidInput)
	}
	if in.Slug == "" {
		return nil, fmt.Errorf("%w: slug is required", apperror.ErrInvalidInput)
	}

	r := &domain.Role{
		Name:        in.Name,
		Slug:        in.Slug,
		Description: in.Description,
	}
	return s.repo.Create(ctx, r)
}

func (s *Service) List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.Role], error) {
	return s.repo.List(ctx, page)
}

func (s *Service) GetByID(ctx context.Context, id string) (*domain.Role, error) {
	if id == "" {
		return nil, fmt.Errorf("%w: id is required", apperror.ErrInvalidInput)
	}
	return s.repo.GetByID(ctx, id)
}

func (s *Service) Update(ctx context.Context, id string, fields repository.UpdateFields) (*domain.Role, error) {
	if id == "" {
		return nil, fmt.Errorf("%w: id is required", apperror.ErrInvalidInput)
	}
	return s.repo.Update(ctx, id, fields)
}

func (s *Service) Delete(ctx context.Context, id string) error {
	if id == "" {
		return fmt.Errorf("%w: id is required", apperror.ErrInvalidInput)
	}
	return s.repo.Delete(ctx, id)
}

func (s *Service) AssignPermission(ctx context.Context, roleID, permissionID string) error {
	if roleID == "" || permissionID == "" {
		return fmt.Errorf("%w: role_id and permission_id are required", apperror.ErrInvalidInput)
	}
	return s.repo.AssignPermission(ctx, roleID, permissionID)
}

func (s *Service) RemovePermission(ctx context.Context, roleID, permissionID string) error {
	if roleID == "" || permissionID == "" {
		return fmt.Errorf("%w: role_id and permission_id are required", apperror.ErrInvalidInput)
	}
	return s.repo.RemovePermission(ctx, roleID, permissionID)
}

func (s *Service) ListPermissions(ctx context.Context, roleID string) ([]permission_domain.Permission, error) {
	if roleID == "" {
		return nil, fmt.Errorf("%w: role_id is required", apperror.ErrInvalidInput)
	}
	return s.repo.ListPermissions(ctx, roleID)
}
