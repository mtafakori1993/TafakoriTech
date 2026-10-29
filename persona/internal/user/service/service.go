package service

import (
	"context"
	"fmt"

	role_domain "github.com/mtafakori/persona/internal/role/domain"
	team_domain "github.com/mtafakori/persona/internal/team/domain"
	"github.com/mtafakori/persona/internal/user/domain"
	"github.com/mtafakori/persona/internal/user/repository"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

// Service provides business logic for user management.
type Service struct {
	repo repository.Repository
}

// New returns a new user Service backed by the given repository.
func New(repo repository.Repository) *Service {
	return &Service{repo: repo}
}

// CreateInput holds the required data for creating a new user.
type CreateInput struct {
	FirstName string
	LastName  string
	Email     string
	Phone     *string
	AvatarURL *string
	Status    *domain.Status
}

func (s *Service) Create(ctx context.Context, in CreateInput) (*domain.User, error) {
	if in.FirstName == "" {
		return nil, fmt.Errorf("%w: first_name is required", apperror.ErrInvalidInput)
	}
	if in.LastName == "" {
		return nil, fmt.Errorf("%w: last_name is required", apperror.ErrInvalidInput)
	}
	if in.Email == "" {
		return nil, fmt.Errorf("%w: email is required", apperror.ErrInvalidInput)
	}

	status := domain.StatusActive
	if in.Status != nil {
		status = *in.Status
	}

	u := &domain.User{
		FirstName: in.FirstName,
		LastName:  in.LastName,
		Email:     in.Email,
		Phone:     in.Phone,
		AvatarURL: in.AvatarURL,
		Status:    status,
	}

	return s.repo.Create(ctx, u)
}

func (s *Service) List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.User], error) {
	return s.repo.List(ctx, page)
}

func (s *Service) GetByID(ctx context.Context, id string) (*domain.User, error) {
	if id == "" {
		return nil, fmt.Errorf("%w: id is required", apperror.ErrInvalidInput)
	}
	return s.repo.GetByID(ctx, id)
}

func (s *Service) GetByEmail(ctx context.Context, email string) (*domain.User, error) {
	if email == "" {
		return nil, fmt.Errorf("%w: email is required", apperror.ErrInvalidInput)
	}
	return s.repo.GetByEmail(ctx, email)
}

func (s *Service) GetByIdentifier(ctx context.Context, identifier string) (*domain.User, error) {
	if identifier == "" {
		return nil, fmt.Errorf("%w: identifier is required", apperror.ErrInvalidInput)
	}
	return s.repo.GetByIdentifier(ctx, identifier)
}

func (s *Service) Update(ctx context.Context, id string, fields repository.UpdateFields) (*domain.User, error) {
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

func (s *Service) AssignRole(ctx context.Context, userID, roleID string) error {
	if userID == "" || roleID == "" {
		return fmt.Errorf("%w: user_id and role_id are required", apperror.ErrInvalidInput)
	}
	return s.repo.AssignRole(ctx, userID, roleID)
}

func (s *Service) RemoveRole(ctx context.Context, userID, roleID string) error {
	if userID == "" || roleID == "" {
		return fmt.Errorf("%w: user_id and role_id are required", apperror.ErrInvalidInput)
	}
	return s.repo.RemoveRole(ctx, userID, roleID)
}

func (s *Service) ListRoles(ctx context.Context, userID string) ([]role_domain.Role, error) {
	if userID == "" {
		return nil, fmt.Errorf("%w: user_id is required", apperror.ErrInvalidInput)
	}
	return s.repo.ListRoles(ctx, userID)
}

func (s *Service) ListTeams(ctx context.Context, userID string) ([]team_domain.Team, error) {
	if userID == "" {
		return nil, fmt.Errorf("%w: user_id is required", apperror.ErrInvalidInput)
	}
	return s.repo.ListTeams(ctx, userID)
}
