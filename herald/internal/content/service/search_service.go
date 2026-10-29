package service

import (
	"context"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/content/repository"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type SearchService interface {
	Search(ctx context.Context, q string, p pagination.Pagination) ([]*domain.Content, int64, error)
}

type searchService struct {
	contentRepo repository.ContentRepository
}

func NewSearchService(cr repository.ContentRepository) SearchService {
	return &searchService{contentRepo: cr}
}

func (s *searchService) Search(ctx context.Context, q string, p pagination.Pagination) ([]*domain.Content, int64, error) {
	return s.contentRepo.Search(ctx, q, p)
}
