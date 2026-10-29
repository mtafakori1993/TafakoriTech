package gormsql

import (
	"context"
	"errors"
	"time"

	"gorm.io/gorm"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type contentRepo struct{ db *gorm.DB }

func NewContentRepository(db *gorm.DB) *contentRepo {
	return &contentRepo{db: db}
}

func (r *contentRepo) FindByID(ctx context.Context, id uint) (*domain.Content, error) {
	var c domain.Content
	err := r.db.WithContext(ctx).First(&c, id).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &c, err
}

func (r *contentRepo) FindBySlug(ctx context.Context, slug string) (*domain.Content, error) {
	var c domain.Content
	err := r.db.WithContext(ctx).Where("slug = ?", slug).First(&c).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &c, err
}

func (r *contentRepo) Update(ctx context.Context, content *domain.Content) error {
	return r.db.WithContext(ctx).Save(content).Error
}

func (r *contentRepo) Search(ctx context.Context, q string, p pagination.Pagination) ([]*domain.Content, int64, error) {
	var items []*domain.Content
	var total int64
	like := "%" + q + "%"

	base := r.db.WithContext(ctx).Model(&domain.Content{}).
		Where("status = ?", domain.StatusPublished).
		Where("title ILIKE ? OR body ILIKE ? OR excerpt ILIKE ?", like, like, like)

	if err := base.Count(&total).Error; err != nil {
		return nil, 0, err
	}
	err := base.Order("published_at DESC").
		Offset(p.Offset()).Limit(p.Limit).
		Find(&items).Error
	return items, total, err
}

func (r *contentRepo) Publish(ctx context.Context, id uint) error {
	now := time.Now()
	return r.db.WithContext(ctx).Model(&domain.Content{}).
		Where("id = ?", id).
		Updates(map[string]any{
			"status":       domain.StatusPublished,
			"published_at": &now,
		}).Error
}

func (r *contentRepo) Unpublish(ctx context.Context, id uint) error {
	return r.db.WithContext(ctx).Model(&domain.Content{}).
		Where("id = ?", id).
		Updates(map[string]any{
			"status":       domain.StatusDraft,
			"published_at": nil,
		}).Error
}
