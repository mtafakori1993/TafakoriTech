package service

import (
	"context"
	"fmt"

	"github.com/mtafakori/persona/internal/permission/domain"
	"github.com/mtafakori/persona/internal/permission/repository"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

// Service provides business logic for permission management.
type Service struct {
	repo repository.Repository
}

// New returns a new permission Service backed by the given repository.
func New(repo repository.Repository) *Service {
	return &Service{repo: repo}
}

// CreateInput holds the required data for creating a new permission.
type CreateInput struct {
	Name        string
	Slug        string
	Resource    string
	Action      string
	Description string
}

func (s *Service) Create(ctx context.Context, in CreateInput) (*domain.Permission, error) {
	if in.Name == "" {
		return nil, fmt.Errorf("%w: name is required", apperror.ErrInvalidInput)
	}
	if in.Slug == "" {
		return nil, fmt.Errorf("%w: slug is required", apperror.ErrInvalidInput)
	}
	if in.Resource == "" {
		return nil, fmt.Errorf("%w: resource is required", apperror.ErrInvalidInput)
	}
	if in.Action == "" {
		return nil, fmt.Errorf("%w: action is required", apperror.ErrInvalidInput)
	}

	p := &domain.Permission{
		Name:        in.Name,
		Slug:        in.Slug,
		Resource:    in.Resource,
		Action:      in.Action,
		Description: in.Description,
	}
	return s.repo.Create(ctx, p)
}

func (s *Service) List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.Permission], error) {
	return s.repo.List(ctx, page)
}

func (s *Service) GetByID(ctx context.Context, id string) (*domain.Permission, error) {
	if id == "" {
		return nil, fmt.Errorf("%w: id is required", apperror.ErrInvalidInput)
	}
	return s.repo.GetByID(ctx, id)
}

func (s *Service) Update(ctx context.Context, id string, fields repository.UpdateFields) (*domain.Permission, error) {
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
