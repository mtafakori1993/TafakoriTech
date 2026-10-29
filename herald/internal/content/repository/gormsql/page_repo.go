package gormsql

import (
	"context"
	"errors"

	"gorm.io/gorm"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type pageRepo struct{ db *gorm.DB }

func NewPageRepository(db *gorm.DB) *pageRepo {
	return &pageRepo{db: db}
}

func (r *pageRepo) FindAll(ctx context.Context, p pagination.Pagination) ([]*domain.Page, int64, error) {
	var pages []*domain.Page
	var total int64

	if err := r.db.WithContext(ctx).Model(&domain.Page{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := r.db.WithContext(ctx).
		Preload("Content").
		Order("id ASC").
		Offset(p.Offset()).Limit(p.Limit).
		Find(&pages).Error
	return pages, total, err
}

func (r *pageRepo) FindBySlug(ctx context.Context, slug string) (*domain.Page, error) {
	var p domain.Page
	err := r.db.WithContext(ctx).
		Preload("Content").
		Joins("JOIN contents ON contents.id = pages.content_id").
		Where("contents.slug = ?", slug).
		Where("contents.status = ?", domain.StatusPublished).
		First(&p).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &p, err
}

func (r *pageRepo) FindByContentID(ctx context.Context, contentID uint) (*domain.Page, error) {
	var p domain.Page
	err := r.db.WithContext(ctx).
		Preload("Content").
		Where("content_id = ?", contentID).
		First(&p).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &p, err
}

func (r *pageRepo) Create(ctx context.Context, page *domain.Page) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(&page.Content).Error; err != nil {
			return err
		}
		page.ContentID = page.Content.ID
		return tx.Omit("Content").Create(page).Error
	})
}

func (r *pageRepo) Update(ctx context.Context, page *domain.Page) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Save(&page.Content).Error; err != nil {
			return err
		}
		return tx.Omit("Content").Save(page).Error
	})
}

func (r *pageRepo) Delete(ctx context.Context, contentID uint) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("content_id = ?", contentID).Delete(&domain.Page{}).Error; err != nil {
			return err
		}
		return tx.Delete(&domain.Content{}, contentID).Error
	})
}
