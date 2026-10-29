package gormsql

import (
	"context"
	"errors"

	"gorm.io/gorm"

	"github.com/mtafakori/herald/internal/content/domain"
)

type tagRepo struct{ db *gorm.DB }

func NewTagRepository(db *gorm.DB) *tagRepo {
	return &tagRepo{db: db}
}

func (r *tagRepo) FindAll(ctx context.Context) ([]*domain.Tag, error) {
	var tags []*domain.Tag
	err := r.db.WithContext(ctx).Order("name ASC").Find(&tags).Error
	return tags, err
}

func (r *tagRepo) FindOrCreate(ctx context.Context, name, slug string) (*domain.Tag, error) {
	var tag domain.Tag
	err := r.db.WithContext(ctx).Where("slug = ?", slug).First(&tag).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		tag = domain.Tag{Name: name, Slug: slug}
		if err := r.db.WithContext(ctx).Create(&tag).Error; err != nil {
			return nil, err
		}
		return &tag, nil
	}
	return &tag, err
}
