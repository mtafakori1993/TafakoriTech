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

type CreatePostInput struct {
	Title          string `validate:"required,min=1,max=500"`
	Slug           string
	Body           string
	Excerpt        string
	CategoryID     *uint
	Tags           []string
	SEOTitle       string
	SEODescription string
	AuthorID       uint
}

type UpdatePostInput struct {
	Title          string `validate:"required,min=1,max=500"`
	Body           string
	Excerpt        string
	Tags           []string
	SEOTitle       string
	SEODescription string
}

type PostService interface {
	List(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error)
	ListAdmin(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error)
	GetBySlug(ctx context.Context, slug string) (*domain.Post, error)
	Create(ctx context.Context, in CreatePostInput) (*domain.Post, error)
	Update(ctx context.Context, contentID uint, in UpdatePostInput) (*domain.Post, error)
	Delete(ctx context.Context, contentID uint) error
}

type postService struct {
	postRepo repository.PostRepository
	tagRepo  repository.TagRepository
	log      *zap.Logger
}

func NewPostService(pr repository.PostRepository, tr repository.TagRepository, log *zap.Logger) PostService {
	return &postService{postRepo: pr, tagRepo: tr, log: log}
}

func (s *postService) List(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error) {
	return s.postRepo.FindAll(ctx, p)
}

func (s *postService) ListAdmin(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error) {
	return s.postRepo.FindAllAdmin(ctx, p)
}

func (s *postService) Update(ctx context.Context, contentID uint, in UpdatePostInput) (*domain.Post, error) {
	post, err := s.postRepo.FindByContentID(ctx, contentID)
	if err != nil {
		return nil, err
	}
	if post == nil {
		return nil, apperror.NotFound("post not found")
	}

	tags, err := s.resolveTags(ctx, in.Tags)
	if err != nil {
		return nil, err
	}

	post.Content.Title = in.Title
	post.Content.Body = in.Body
	post.Content.Excerpt = in.Excerpt
	post.Content.SEOTitle = in.SEOTitle
	post.Content.SEODescription = in.SEODescription
	post.Tags = tags

	if err := s.postRepo.Update(ctx, post); err != nil {
		return nil, err
	}
	return post, nil
}

func (s *postService) Delete(ctx context.Context, contentID uint) error {
	return s.postRepo.Delete(ctx, contentID)
}

func (s *postService) GetBySlug(ctx context.Context, sl string) (*domain.Post, error) {
	post, err := s.postRepo.FindBySlug(ctx, sl)
	if err != nil {
		return nil, err
	}
	if post == nil {
		return nil, apperror.NotFound(fmt.Sprintf("post %q not found", sl))
	}
	return post, nil
}

func (s *postService) Create(ctx context.Context, in CreatePostInput) (*domain.Post, error) {
	sl := in.Slug
	if sl == "" {
		sl = slug.Make(in.Title)
	}

	tags, err := s.resolveTags(ctx, in.Tags)
	if err != nil {
		return nil, err
	}

	post := &domain.Post{
		Content: domain.Content{
			Type:           domain.ContentTypePost,
			Title:          in.Title,
			Slug:           sl,
			Body:           in.Body,
			Excerpt:        in.Excerpt,
			Status:         domain.StatusDraft,
			SEOTitle:       in.SEOTitle,
			SEODescription: in.SEODescription,
			AuthorID:       in.AuthorID,
		},
		CategoryID: in.CategoryID,
		Tags:       tags,
	}

	if err := s.postRepo.Create(ctx, post); err != nil {
		return nil, err
	}
	s.log.Info("post created", zap.String("slug", sl))
	return post, nil
}

func (s *postService) resolveTags(ctx context.Context, names []string) ([]domain.Tag, error) {
	tags := make([]domain.Tag, 0, len(names))
	for _, name := range names {
		t, err := s.tagRepo.FindOrCreate(ctx, name, slug.Make(name))
		if err != nil {
			return nil, err
		}
		tags = append(tags, *t)
	}
	return tags, nil
}
