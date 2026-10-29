package gormsql

import (
	"context"
	"errors"

	"gorm.io/gorm"

	"github.com/mtafakori/herald/internal/content/domain"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type postRepo struct{ db *gorm.DB }

func NewPostRepository(db *gorm.DB) *postRepo {
	return &postRepo{db: db}
}

func (r *postRepo) FindAll(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error) {
	var posts []*domain.Post
	var total int64

	countQ := r.db.WithContext(ctx).Model(&domain.Post{}).
		Joins("JOIN contents ON contents.id = posts.content_id")
	if err := countQ.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := r.db.WithContext(ctx).
		Preload("Content").Preload("Category").Preload("Tags").
		Joins("JOIN contents ON contents.id = posts.content_id").
		Order("contents.created_at DESC").
		Offset(p.Offset()).Limit(p.Limit).
		Find(&posts).Error
	return posts, total, err
}

func (r *postRepo) FindAllAdmin(ctx context.Context, p pagination.Pagination) ([]*domain.Post, int64, error) {
	var posts []*domain.Post
	var total int64

	if err := r.db.WithContext(ctx).Model(&domain.Post{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := r.db.WithContext(ctx).
		Preload("Content").Preload("Category").Preload("Tags").
		Order("id DESC").
		Offset(p.Offset()).Limit(p.Limit).
		Find(&posts).Error
	return posts, total, err
}

func (r *postRepo) FindByContentID(ctx context.Context, contentID uint) (*domain.Post, error) {
	var post domain.Post
	err := r.db.WithContext(ctx).
		Preload("Content").Preload("Category").Preload("Tags").
		Where("content_id = ?", contentID).
		First(&post).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &post, err
}

func (r *postRepo) Update(ctx context.Context, post *domain.Post) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Save(&post.Content).Error; err != nil {
			return err
		}
		if err := tx.Omit("Content").Save(post).Error; err != nil {
			return err
		}
		return tx.Model(post).Association("Tags").Replace(post.Tags)
	})
}

func (r *postRepo) Delete(ctx context.Context, contentID uint) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var post domain.Post
		if err := tx.Where("content_id = ?", contentID).First(&post).Error; err != nil {
			return err
		}
		if err := tx.Model(&post).Association("Tags").Clear(); err != nil {
			return err
		}
		if err := tx.Delete(&post).Error; err != nil {
			return err
		}
		return tx.Delete(&domain.Content{}, contentID).Error
	})
}

func (r *postRepo) FindBySlug(ctx context.Context, slug string) (*domain.Post, error) {
	var post domain.Post
	err := r.db.WithContext(ctx).
		Preload("Content").Preload("Category").Preload("Tags").
		Joins("JOIN contents ON contents.id = posts.content_id").
		Where("contents.slug = ?", slug).
		Where("contents.status = ?", domain.StatusPublished).
		First(&post).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &post, err
}

func (r *postRepo) Create(ctx context.Context, post *domain.Post) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(&post.Content).Error; err != nil {
			return err
		}
		post.ContentID = post.Content.ID
		return tx.Omit("Content").Create(post).Error
	})
}
