package service

import (
	"context"
	"fmt"

	"github.com/gosimple/slug"
	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/content/repository"
	"github.com/mtafakori/herald/internal/shared/apperror"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type CreatePageInput struct {
	Title          string `validate:"required,min=1,max=500"`
	Slug           string
	Body           string
	Excerpt        string
	Route          string `validate:"required"`
	Template       string
	IsSystem       bool
	SEOTitle       string
	SEODescription string
	AuthorID       uint
}

type UpdatePageInput struct {
	Title          string `validate:"required,min=1,max=500"`
	Body           string
	Excerpt        string
	Route          string `validate:"required"`
	Template       string
	SEOTitle       string
	SEODescription string
}

type PageService interface {
	List(ctx context.Context, p pagination.Pagination) ([]*domain.Page, int64, error)
	GetBySlug(ctx context.Context, slug string) (*domain.Page, error)
	Create(ctx context.Context, in CreatePageInput) (*domain.Page, error)
	Update(ctx context.Context, contentID uint, in UpdatePageInput) (*domain.Page, error)
	Delete(ctx context.Context, contentID uint) error
}

type pageService struct {
	pageRepo    repository.PageRepository
	contentRepo repository.ContentRepository
	log         *zap.Logger
}

func NewPageService(pr repository.PageRepository, cr repository.ContentRepository, log *zap.Logger) PageService {
	return &pageService{pageRepo: pr, contentRepo: cr, log: log}
}

func (s *pageService) List(ctx context.Context, p pagination.Pagination) ([]*domain.Page, int64, error) {
	return s.pageRepo.FindAll(ctx, p)
}

func (s *pageService) GetBySlug(ctx context.Context, sl string) (*domain.Page, error) {
	page, err := s.pageRepo.FindBySlug(ctx, sl)
	if err != nil {
		return nil, err
	}
	if page == nil {
		return nil, apperror.NotFound(fmt.Sprintf("page %q not found", sl))
	}
	return page, nil
}

func (s *pageService) Create(ctx context.Context, in CreatePageInput) (*domain.Page, error) {
	sl := in.Slug
	if sl == "" {
		sl = slug.Make(in.Title)
	}
	tmpl := in.Template
	if tmpl == "" {
		tmpl = "default"
	}

	page := &domain.Page{
		Content: domain.Content{
			Type:           domain.ContentTypePage,
			Title:          in.Title,
			Slug:           sl,
			Body:           in.Body,
			Excerpt:        in.Excerpt,
			Status:         domain.StatusDraft,
			SEOTitle:       in.SEOTitle,
			SEODescription: in.SEODescription,
			AuthorID:       in.AuthorID,
		},
		Route:    in.Route,
		Template: tmpl,
		IsSystem: in.IsSystem,
	}

	if err := s.pageRepo.Create(ctx, page); err != nil {
		return nil, err
	}
	s.log.Info("page created", zap.String("slug", sl), zap.String("route", in.Route))
	return page, nil
}

func (s *pageService) Update(ctx context.Context, contentID uint, in UpdatePageInput) (*domain.Page, error) {
	content, err := s.contentRepo.FindByID(ctx, contentID)
	if err != nil {
		return nil, err
	}
	if content == nil {
		return nil, apperror.NotFound("page not found")
	}

	page, err := s.pageRepo.FindByContentID(ctx, contentID)
	if err != nil {
		return nil, err
	}
	if page == nil {
		return nil, apperror.NotFound("page not found")
	}

	content.Title = in.Title
	content.Body = in.Body
	content.Excerpt = in.Excerpt
	content.SEOTitle = in.SEOTitle
	content.SEODescription = in.SEODescription
	page.Content = *content
	page.Route = in.Route
	if in.Template != "" {
		page.Template = in.Template
	}

	if err := s.pageRepo.Update(ctx, page); err != nil {
		return nil, err
	}
	return page, nil
}

func (s *pageService) Delete(ctx context.Context, contentID uint) error {
	return s.pageRepo.Delete(ctx, contentID)
}
