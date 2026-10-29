package service

import (
	"context"
	"fmt"
	"strings"

	"github.com/gosimple/slug"
	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/content/repository"
	"github.com/mtafakori/herald/internal/shared/apperror"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type CreateArticleInput struct {
	Title          string `validate:"required,min=1,max=500"`
	Slug           string
	Body           string
	Excerpt        string
	Tags           []string
	SEOTitle       string
	SEODescription string
	AuthorID       uint
}

type UpdateArticleInput struct {
	Title          string `validate:"required,min=1,max=500"`
	Body           string
	Excerpt        string
	Tags           []string
	SEOTitle       string
	SEODescription string
}

type ArticleService interface {
	List(ctx context.Context, p pagination.Pagination) ([]*domain.Article, int64, error)
	ListAdmin(ctx context.Context, p pagination.Pagination) ([]*domain.Article, int64, error)
	GetBySlug(ctx context.Context, slug string) (*domain.Article, error)
	Create(ctx context.Context, in CreateArticleInput) (*domain.Article, error)
	Update(ctx context.Context, contentID uint, in UpdateArticleInput) (*domain.Article, error)
	Delete(ctx context.Context, contentID uint) error
}

type articleService struct {
	articleRepo repository.ArticleRepository
	tagRepo     repository.TagRepository
	log         *zap.Logger
}

func NewArticleService(ar repository.ArticleRepository, tr repository.TagRepository, log *zap.Logger) ArticleService {
	return &articleService{articleRepo: ar, tagRepo: tr, log: log}
}

func (s *articleService) List(ctx context.Context, p pagination.Pagination) ([]*domain.Article, int64, error) {
	return s.articleRepo.FindAll(ctx, p)
}

func (s *articleService) ListAdmin(ctx context.Context, p pagination.Pagination) ([]*domain.Article, int64, error) {
	return s.articleRepo.FindAllAdmin(ctx, p)
}

func (s *articleService) Update(ctx context.Context, contentID uint, in UpdateArticleInput) (*domain.Article, error) {
	article, err := s.articleRepo.FindByContentID(ctx, contentID)
	if err != nil {
		return nil, err
	}
	if article == nil {
		return nil, apperror.NotFound("article not found")
	}

	tags := make([]domain.Tag, 0, len(in.Tags))
	for _, name := range in.Tags {
		t, err := s.tagRepo.FindOrCreate(ctx, name, slug.Make(name))
		if err != nil {
			return nil, err
		}
		tags = append(tags, *t)
	}

	article.Content.Title = in.Title
	article.Content.Body = in.Body
	article.Content.Excerpt = in.Excerpt
	article.Content.SEOTitle = in.SEOTitle
	article.Content.SEODescription = in.SEODescription
	article.Tags = tags
	article.ReadTime = estimateReadTime(in.Body)

	if err := s.articleRepo.Update(ctx, article); err != nil {
		return nil, err
	}
	return article, nil
}

func (s *articleService) Delete(ctx context.Context, contentID uint) error {
	return s.articleRepo.Delete(ctx, contentID)
}

func (s *articleService) GetBySlug(ctx context.Context, sl string) (*domain.Article, error) {
	article, err := s.articleRepo.FindBySlug(ctx, sl)
	if err != nil {
		return nil, err
	}
	if article == nil {
		return nil, apperror.NotFound(fmt.Sprintf("article %q not found", sl))
	}
	return article, nil
}

func (s *articleService) Create(ctx context.Context, in CreateArticleInput) (*domain.Article, error) {
	sl := in.Slug
	if sl == "" {
		sl = slug.Make(in.Title)
	}

	tags := make([]domain.Tag, 0, len(in.Tags))
	for _, name := range in.Tags {
		t, err := s.tagRepo.FindOrCreate(ctx, name, slug.Make(name))
		if err != nil {
			return nil, err
		}
		tags = append(tags, *t)
	}

	article := &domain.Article{
		Content: domain.Content{
			Type:           domain.ContentTypeArticle,
			Title:          in.Title,
			Slug:           sl,
			Body:           in.Body,
			Excerpt:        in.Excerpt,
			Status:         domain.StatusDraft,
			SEOTitle:       in.SEOTitle,
			SEODescription: in.SEODescription,
			AuthorID:       in.AuthorID,
		},
		ReadTime: estimateReadTime(in.Body),
		Tags:     tags,
	}

	if err := s.articleRepo.Create(ctx, article); err != nil {
		return nil, err
	}
	s.log.Info("article created", zap.String("slug", sl))
	return article, nil
}

// estimateReadTime returns approximate read time in minutes (200 wpm average).
func estimateReadTime(body string) int {
	words := len(strings.Fields(body))
	t := words / 200
	if t < 1 {
		t = 1
	}
	return t
}
