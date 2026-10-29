package service

import (
	"context"
	"fmt"

	"github.com/mtafakori/persona/internal/team/domain"
	"github.com/mtafakori/persona/internal/team/repository"
	user_domain "github.com/mtafakori/persona/internal/user/domain"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

// Service provides business logic for team management.
type Service struct {
	repo repository.Repository
}

// New returns a new team Service backed by the given repository.
func New(repo repository.Repository) *Service {
	return &Service{repo: repo}
}

// CreateInput holds the required data for creating a new team.
type CreateInput struct {
	Name        string
	Description string
}

func (s *Service) Create(ctx context.Context, in CreateInput) (*domain.Team, error) {
	if in.Name == "" {
		return nil, fmt.Errorf("%w: name is required", apperror.ErrInvalidInput)
	}

	t := &domain.Team{
		Name:        in.Name,
		Description: in.Description,
	}
	return s.repo.Create(ctx, t)
}

func (s *Service) List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.Team], error) {
	return s.repo.List(ctx, page)
}

func (s *Service) GetByID(ctx context.Context, id string) (*domain.Team, error) {
	if id == "" {
		return nil, fmt.Errorf("%w: id is required", apperror.ErrInvalidInput)
	}
	return s.repo.GetByID(ctx, id)
}

func (s *Service) Update(ctx context.Context, id string, fields repository.UpdateFields) (*domain.Team, error) {
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

func (s *Service) AddUser(ctx context.Context, teamID, userID string) error {
	if teamID == "" || userID == "" {
		return fmt.Errorf("%w: team_id and user_id are required", apperror.ErrInvalidInput)
	}
	return s.repo.AddUser(ctx, teamID, userID)
}

func (s *Service) RemoveUser(ctx context.Context, teamID, userID string) error {
	if teamID == "" || userID == "" {
		return fmt.Errorf("%w: team_id and user_id are required", apperror.ErrInvalidInput)
	}
	return s.repo.RemoveUser(ctx, teamID, userID)
}

func (s *Service) ListUsers(ctx context.Context, teamID string) ([]user_domain.User, error) {
	if teamID == "" {
		return nil, fmt.Errorf("%w: team_id is required", apperror.ErrInvalidInput)
	}
	return s.repo.ListUsers(ctx, teamID)
}
