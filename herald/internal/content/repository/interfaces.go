package repository

import (
	"context"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type ContentRepository interface {
	FindByID(ctx context.Context, id uint) (*domain.Content, error)
	FindBySlug(ctx context.Context, slug string) (*domain.Content, error)
	Update(ctx context.Context, content *domain.Content) error
	Search(ctx context.Context, q string, p pagination.Pagination) ([]*domain.Content, int64, error)
	Publish(ctx context.Context, id uint) error
	Unpublish(ctx context.Context, id uint) error
}

type PageRepository interface {
	FindAll(ctx context.Context, p pagination.Pagination) ([]*domain.Page, int64, error)
	FindBySlug(ctx context.Context, slug string) (*domain.Page, error)
	FindByContentID(ctx context.Context, contentID uint) (*domain.Page, error)
	Create(ctx context.Context, page *domain.Page) error
	Update(ctx context.Context, page *domain.Page) error
	Delete(ctx context.Context, contentID uint) error
}

type PostRepository interface {
	FindAll(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error)
	FindAllAdmin(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error)
	FindBySlug(ctx context.Context, slug string) (*domain.Post, error)
	FindByContentID(ctx context.Context, contentID uint) (*domain.Post, error)
	Create(ctx context.Context, post *domain.Post) error
	Update(ctx context.Context, post *domain.Post) error
	Delete(ctx context.Context, contentID uint) error
}

type ArticleRepository interface {
	FindAll(ctx context.Context, p pagination.Pagination) ([]*domain.Article, int64, error)
	FindAllAdmin(ctx context.Context, p pagination.Pagination) ([]*domain.Article, int64, error)
	FindBySlug(ctx context.Context, slug string) (*domain.Article, error)
	FindByContentID(ctx context.Context, contentID uint) (*domain.Article, error)
	Create(ctx context.Context, article *domain.Article) error
	Update(ctx context.Context, article *domain.Article) error
	Delete(ctx context.Context, contentID uint) error
}

type CategoryRepository interface {
	FindAll(ctx context.Context) ([]*domain.Category, error)
	FindByID(ctx context.Context, id uint) (*domain.Category, error)
	Create(ctx context.Context, cat *domain.Category) error
}

type TagRepository interface {
	FindAll(ctx context.Context) ([]*domain.Tag, error)
	FindOrCreate(ctx context.Context, name, slug string) (*domain.Tag, error)
}
