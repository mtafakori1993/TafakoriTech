package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/service"
	"github.com/mtafakori/herald/internal/content/transport/rest/dto"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type SearchHandler struct {
	svc service.SearchService
	log *zap.Logger
}

func NewSearchHandler(svc service.SearchService, log *zap.Logger) *SearchHandler {
	return &SearchHandler{svc: svc, log: log}
}

// Search godoc
//
//	@Summary		Full-text search across all published content
//	@Tags			search
//	@Produce		json
//	@Param			q		query		string	true	"Search query"
//	@Param			page	query		int		false	"Page number"
//	@Param			limit	query		int		false	"Items per page"
//	@Success		200		{object}	pagination.Response[dto.ContentResponse]
//	@Failure		400		{object}	dto.ErrorResponse
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/search [get]
func (h *SearchHandler) Search(c *gin.Context) {
	q := c.Query("q")
	if q == "" {
		c.JSON(http.StatusBadRequest, dto.ErrorResponse{Error: "query parameter 'q' is required"})
		return
	}

	p := pagination.FromQuery(c)
	results, total, err := h.svc.Search(c.Request.Context(), q, p)
	if err != nil {
		c.JSON(http.StatusInternalServerError, dto.ErrorResponse{Error: err.Error()})
		return
	}

	mapped := make([]dto.ContentResponse, 0, len(results))
	for _, item := range results {
		mapped = append(mapped, dto.ContentResponse{
			ID:             item.ID,
			Type:           string(item.Type),
			Title:          item.Title,
			Slug:           item.Slug,
			Excerpt:        item.Excerpt,
			Status:         string(item.Status),
			SEOTitle:       item.SEOTitle,
			SEODescription: item.SEODescription,
			AuthorID:       item.AuthorID,
			PublishedAt:    item.PublishedAt,
			CreatedAt:      item.CreatedAt,
			UpdatedAt:      item.UpdatedAt,
		})
	}
	c.JSON(http.StatusOK, pagination.NewResponse(mapped, total, p))
}
