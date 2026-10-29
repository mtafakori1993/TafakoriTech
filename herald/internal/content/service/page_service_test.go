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

// --- mock page repository ---

type mockPageRepo struct {
	findBySlugFn      func(ctx context.Context, slug string) (*domain.Page, error)
	findByContentIDFn func(ctx context.Context, id uint) (*domain.Page, error)
	createFn          func(ctx context.Context, p *domain.Page) error
	updateFn          func(ctx context.Context, p *domain.Page) error
	deleteFn          func(ctx context.Context, id uint) error
}

func (m *mockPageRepo) FindBySlug(ctx context.Context, slug string) (*domain.Page, error) {
	return m.findBySlugFn(ctx, slug)
}
func (m *mockPageRepo) FindByContentID(ctx context.Context, id uint) (*domain.Page, error) {
	return m.findByContentIDFn(ctx, id)
}
func (m *mockPageRepo) Create(ctx context.Context, p *domain.Page) error {
	return m.createFn(ctx, p)
}
func (m *mockPageRepo) Update(ctx context.Context, p *domain.Page) error {
	return m.updateFn(ctx, p)
}
func (m *mockPageRepo) Delete(ctx context.Context, id uint) error {
	return m.deleteFn(ctx, id)
}

// --- mock content repository ---

type mockContentRepo struct {
	findByIDFn   func(ctx context.Context, id uint) (*domain.Content, error)
	findBySlugFn func(ctx context.Context, slug string) (*domain.Content, error)
	updateFn     func(ctx context.Context, c *domain.Content) error
	searchFn     func(ctx context.Context, q string, p interface{}) ([]*domain.Content, int64, error)
	publishFn    func(ctx context.Context, id uint) error
	unpublishFn  func(ctx context.Context, id uint) error
}

func (m *mockContentRepo) FindByID(ctx context.Context, id uint) (*domain.Content, error) {
	return m.findByIDFn(ctx, id)
}
func (m *mockContentRepo) FindBySlug(ctx context.Context, slug string) (*domain.Content, error) {
	return m.findBySlugFn(ctx, slug)
}
func (m *mockContentRepo) Update(ctx context.Context, c *domain.Content) error {
	return m.updateFn(ctx, c)
}
func (m *mockContentRepo) Search(ctx context.Context, q string, p pagination.Pagination) ([]*domain.Content, int64, error) {
	return nil, 0, nil
}
func (m *mockContentRepo) Publish(ctx context.Context, id uint) error {
	return m.publishFn(ctx, id)
}
func (m *mockContentRepo) Unpublish(ctx context.Context, id uint) error {
	return m.unpublishFn(ctx, id)
}

// --- tests ---

func TestPageService_GetBySlug_Found(t *testing.T) {
	want := &domain.Page{
		ID: 1,
		Content: domain.Content{Title: "About Us", Slug: "about-us", Status: domain.StatusPublished},
	}
	svc := service.NewPageService(
		&mockPageRepo{findBySlugFn: func(_ context.Context, _ string) (*domain.Page, error) { return want, nil }},
		&mockContentRepo{},
		zap.NewNop(),
	)

	got, err := svc.GetBySlug(context.Background(), "about-us")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got.Content.Slug != "about-us" {
		t.Errorf("expected slug 'about-us', got %q", got.Content.Slug)
	}
}

func TestPageService_GetBySlug_NotFound(t *testing.T) {
	svc := service.NewPageService(
		&mockPageRepo{findBySlugFn: func(_ context.Context, _ string) (*domain.Page, error) { return nil, nil }},
		&mockContentRepo{},
		zap.NewNop(),
	)

	_, err := svc.GetBySlug(context.Background(), "missing")
	if !errors.Is(err, apperror.ErrNotFound) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestPageService_Create(t *testing.T) {
	var created *domain.Page
	svc := service.NewPageService(
		&mockPageRepo{createFn: func(_ context.Context, p *domain.Page) error {
			created = p
			return nil
		}},
		&mockContentRepo{},
		zap.NewNop(),
	)

	page, err := svc.Create(context.Background(), service.CreatePageInput{
		Title:    "About Us",
		Route:    "/about",
		AuthorID: 1,
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if page.Content.Slug == "" {
		t.Error("slug should have been auto-generated")
	}
	if created == nil {
		t.Error("create should have been called")
	}
}

func TestPageService_Create_RepoError(t *testing.T) {
	dbErr := errors.New("db error")
	svc := service.NewPageService(
		&mockPageRepo{createFn: func(_ context.Context, _ *domain.Page) error { return dbErr }},
		&mockContentRepo{},
		zap.NewNop(),
	)

	_, err := svc.Create(context.Background(), service.CreatePageInput{Title: "Test", Route: "/test"})
	if !errors.Is(err, dbErr) {
		t.Errorf("expected db error, got %v", err)
	}
}
