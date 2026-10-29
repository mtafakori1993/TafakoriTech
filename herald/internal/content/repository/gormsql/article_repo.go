package gormsql

import (
	"context"
	"errors"

	"gorm.io/gorm"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type articleRepo struct{ db *gorm.DB }

func NewArticleRepository(db *gorm.DB) *articleRepo {
	return &articleRepo{db: db}
}

func (r *articleRepo) FindAll(ctx context.Context, p pagination.Pagination) ([]*domain.Article, int64, error) {
	var articles []*domain.Article
	var total int64

	countQ := r.db.WithContext(ctx).Model(&domain.Article{}).
		Joins("JOIN contents ON contents.id = articles.content_id")
	if err := countQ.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := r.db.WithContext(ctx).
		Preload("Content").Preload("Tags").
		Joins("JOIN contents ON contents.id = articles.content_id").
		Order("contents.created_at DESC").
		Offset(p.Offset()).Limit(p.Limit).
		Find(&articles).Error
	return articles, total, err
}

func (r *articleRepo) FindAllAdmin(ctx context.Context, p pagination.Pagination) ([]*domain.Article, int64, error) {
	var articles []*domain.Article
	var total int64

	if err := r.db.WithContext(ctx).Model(&domain.Article{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := r.db.WithContext(ctx).
		Preload("Content").Preload("Tags").
		Order("id DESC").
		Offset(p.Offset()).Limit(p.Limit).
		Find(&articles).Error
	return articles, total, err
}

func (r *articleRepo) FindByContentID(ctx context.Context, contentID uint) (*domain.Article, error) {
	var article domain.Article
	err := r.db.WithContext(ctx).
		Preload("Content").Preload("Tags").
		Where("content_id = ?", contentID).
		First(&article).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &article, err
}

func (r *articleRepo) Update(ctx context.Context, article *domain.Article) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Save(&article.Content).Error; err != nil {
			return err
		}
		if err := tx.Omit("Content").Save(article).Error; err != nil {
			return err
		}
		return tx.Model(article).Association("Tags").Replace(article.Tags)
	})
}

func (r *articleRepo) Delete(ctx context.Context, contentID uint) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var article domain.Article
		if err := tx.Where("content_id = ?", contentID).First(&article).Error; err != nil {
			return err
		}
		if err := tx.Model(&article).Association("Tags").Clear(); err != nil {
			return err
		}
		if err := tx.Delete(&article).Error; err != nil {
			return err
		}
		return tx.Delete(&domain.Content{}, contentID).Error
	})
}

func (r *articleRepo) FindBySlug(ctx context.Context, slug string) (*domain.Article, error) {
	var article domain.Article
	err := r.db.WithContext(ctx).
		Preload("Content").Preload("Tags").
		Joins("JOIN contents ON contents.id = articles.content_id").
		Where("contents.slug = ?", slug).
		Where("contents.status = ?", domain.StatusPublished).
		First(&article).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &article, err
}

func (r *articleRepo) Create(ctx context.Context, article *domain.Article) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(&article.Content).Error; err != nil {
			return err
		}
		article.ContentID = article.Content.ID
		return tx.Omit("Content").Create(article).Error
	})
}
