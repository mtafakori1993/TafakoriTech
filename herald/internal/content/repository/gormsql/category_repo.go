package gormsql

import (
	"context"
	"errors"

	"gorm.io/gorm"

	"github.com/mtafakori/herald/internal/content/domain"
)

type categoryRepo struct{ db *gorm.DB }

func NewCategoryRepository(db *gorm.DB) *categoryRepo {
	return &categoryRepo{db: db}
}

func (r *categoryRepo) FindAll(ctx context.Context) ([]*domain.Category, error) {
	var cats []*domain.Category
	err := r.db.WithContext(ctx).Order("name ASC").Find(&cats).Error
	return cats, err
}

func (r *categoryRepo) FindByID(ctx context.Context, id uint) (*domain.Category, error) {
	var cat domain.Category
	err := r.db.WithContext(ctx).First(&cat, id).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, nil
	}
	return &cat, err
}

func (r *categoryRepo) Create(ctx context.Context, cat *domain.Category) error {
	return r.db.WithContext(ctx).Create(cat).Error
}
