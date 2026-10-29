package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/service"
	"github.com/mtafakori/herald/internal/content/transport/rest/dto"
	"github.com/mtafakori/herald/internal/shared/apperror"
)

type PublishHandler struct {
	svc service.PublishService
	log *zap.Logger
}

func NewPublishHandler(svc service.PublishService, log *zap.Logger) *PublishHandler {
	return &PublishHandler{svc: svc, log: log}
}

// Publish godoc
//
//	@Summary		Publish a content item
//	@Tags			publishing
//	@Security		ApiKeyAuth
//	@Param			id	path	int	true	"Content ID"
//	@Success		204	"No Content"
//	@Failure		404	{object}	dto.ErrorResponse
//	@Failure		500	{object}	dto.ErrorResponse
//	@Router			/publish/{id} [post]
func (h *PublishHandler) Publish(c *gin.Context) {
	id, err := parseUintParam(c, "id")
	if err != nil {
		return
	}
	if err := h.svc.Publish(c.Request.Context(), id); err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}

// Unpublish godoc
//
//	@Summary		Unpublish a content item
//	@Tags			publishing
//	@Security		ApiKeyAuth
//	@Param			id	path	int	true	"Content ID"
//	@Success		204	"No Content"
//	@Failure		404	{object}	dto.ErrorResponse
//	@Failure		500	{object}	dto.ErrorResponse
//	@Router			/unpublish/{id} [post]
func (h *PublishHandler) Unpublish(c *gin.Context) {
	id, err := parseUintParam(c, "id")
	if err != nil {
		return
	}
	if err := h.svc.Unpublish(c.Request.Context(), id); err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}
