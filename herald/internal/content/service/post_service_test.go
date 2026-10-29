package service_test

import (
	"context"
	"errors"
	"testing"

	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/content/service"
	"github.com/mtafakori/herald/internal/shared/apperror"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

// --- mock post repository ---

type mockPostRepo struct {
	findAllFn    func(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error)
	findBySlugFn func(ctx context.Context, slug string) (*domain.Post, error)
	createFn     func(ctx context.Context, post *domain.Post) error
}

func (m *mockPostRepo) FindAll(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error) {
	return m.findAllFn(ctx, p)
}
func (m *mockPostRepo) FindBySlug(ctx context.Context, slug string) (*domain.Post, error) {
	return m.findBySlugFn(ctx, slug)
}
func (m *mockPostRepo) Create(ctx context.Context, post *domain.Post) error {
	return m.createFn(ctx, post)
}

// --- mock tag repository ---

type mockTagRepo struct {
	findOrCreateFn func(ctx context.Context, name, slug string) (*domain.Tag, error)
}

func (m *mockTagRepo) FindAll(ctx context.Context) ([]*domain.Tag, error) { return nil, nil }
func (m *mockTagRepo) FindOrCreate(ctx context.Context, name, slug string) (*domain.Tag, error) {
	return m.findOrCreateFn(ctx, name, slug)
}

// --- tests ---

func TestPostService_List(t *testing.T) {
	posts := []*domain.Post{{ID: 1}, {ID: 2}}
	svc := service.NewPostService(
		&mockPostRepo{findAllFn: func(_ context.Context, _ pagination.Pagination) ([]*domain.Post, int64, error) {
			return posts, 2, nil
		}},
		&mockTagRepo{},
		zap.NewNop(),
	)

	got, total, err := svc.List(context.Background(), pagination.Pagination{Page: 1, Limit: 10})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if total != 2 {
		t.Errorf("expected total 2, got %d", total)
	}
	if len(got) != 2 {
		t.Errorf("expected 2 posts, got %d", len(got))
	}
}

func TestPostService_GetBySlug_NotFound(t *testing.T) {
	svc := service.NewPostService(
		&mockPostRepo{findBySlugFn: func(_ context.Context, _ string) (*domain.Post, error) { return nil, nil }},
		&mockTagRepo{},
		zap.NewNop(),
	)

	_, err := svc.GetBySlug(context.Background(), "no-such-post")
	if !errors.Is(err, apperror.ErrNotFound) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestPostService_Create_WithTags(t *testing.T) {
	tagCalls := 0
	svc := service.NewPostService(
		&mockPostRepo{createFn: func(_ context.Context, _ *domain.Post) error { return nil }},
		&mockTagRepo{findOrCreateFn: func(_ context.Context, name, _ string) (*domain.Tag, error) {
			tagCalls++
			return &domain.Tag{Name: name}, nil
		}},
		zap.NewNop(),
	)

	_, err := svc.Create(context.Background(), service.CreatePostInput{
		Title: "Hello World",
		Tags:  []string{"go", "backend"},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if tagCalls != 2 {
		t.Errorf("expected 2 tag lookups, got %d", tagCalls)
	}
}
