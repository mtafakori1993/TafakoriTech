package service

import (
	"context"
	"fmt"

	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/repository"
	"github.com/mtafakori/herald/internal/shared/apperror"
)

type PublishService interface {
	Publish(ctx context.Context, contentID uint) error
	Unpublish(ctx context.Context, contentID uint) error
}

type publishService struct {
	contentRepo repository.ContentRepository
	log         *zap.Logger
}

func NewPublishService(cr repository.ContentRepository, log *zap.Logger) PublishService {
	return &publishService{contentRepo: cr, log: log}
}

func (s *publishService) Publish(ctx context.Context, contentID uint) error {
	c, err := s.contentRepo.FindByID(ctx, contentID)
	if err != nil {
		return err
	}
	if c == nil {
		return apperror.NotFound(fmt.Sprintf("content %d not found", contentID))
	}
	if err := s.contentRepo.Publish(ctx, contentID); err != nil {
		return err
	}
	s.log.Info("content published", zap.Uint("id", contentID))
	return nil
}

func (s *publishService) Unpublish(ctx context.Context, contentID uint) error {
	c, err := s.contentRepo.FindByID(ctx, contentID)
	if err != nil {
		return err
	}
	if c == nil {
		return apperror.NotFound(fmt.Sprintf("content %d not found", contentID))
	}
	if err := s.contentRepo.Unpublish(ctx, contentID); err != nil {
		return err
	}
	s.log.Info("content unpublished", zap.Uint("id", contentID))
	return nil
}
