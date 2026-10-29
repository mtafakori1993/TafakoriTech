package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/service"
	"github.com/mtafakori/herald/internal/content/transport/rest/dto"
	"github.com/mtafakori/herald/internal/shared/apperror"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type PageHandler struct {
	svc service.PageService
	log *zap.Logger
}

func NewPageHandler(svc service.PageService, log *zap.Logger) *PageHandler {
	return &PageHandler{svc: svc, log: log}
}

// List godoc
//
//	@Summary		List all pages
//	@Tags			pages
//	@Security		ApiKeyAuth
//	@Produce		json
//	@Param			page	query		int	false	"Page number"
//	@Param			limit	query		int	false	"Items per page"
//	@Success		200		{object}	pagination.Response[dto.PageResponse]
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/pages [get]
func (h *PageHandler) List(c *gin.Context) {
	p := pagination.FromQuery(c)
	pages, total, err := h.svc.List(c.Request.Context(), p)
	if err != nil {
		c.JSON(http.StatusInternalServerError, dto.ErrorResponse{Error: err.Error()})
		return
	}
	mapped := make([]dto.PageResponse, 0, len(pages))
	for _, page := range pages {
		mapped = append(mapped, dto.MapPage(page))
	}
	c.JSON(http.StatusOK, pagination.NewResponse(mapped, total, p))
}

// GetBySlug godoc
//
//	@Summary		Get page by slug
//	@Tags			pages
//	@Produce		json
//	@Param			slug	path		string	true	"Page slug"
//	@Success		200		{object}	dto.PageResponse
//	@Failure		404		{object}	dto.ErrorResponse
//	@Router			/pages/{slug} [get]
func (h *PageHandler) GetBySlug(c *gin.Context) {
	page, err := h.svc.GetBySlug(c.Request.Context(), c.Param("slug"))
	if err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, dto.MapPage(page))
}

// Create godoc
//
//	@Summary		Create a page
//	@Tags			pages
//	@Security		ApiKeyAuth
//	@Accept			json
//	@Produce		json
//	@Param			body	body		dto.CreatePageRequest	true	"Page data"
//	@Success		201		{object}	dto.PageResponse
//	@Failure		400		{object}	dto.ErrorResponse
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/pages [post]
func (h *PageHandler) Create(c *gin.Context) {
	var req dto.CreatePageRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, dto.ErrorResponse{Error: err.Error()})
		return
	}

	page, err := h.svc.Create(c.Request.Context(), service.CreatePageInput{
		Title:          req.Title,
		Slug:           req.Slug,
		Body:           req.Body,
		Excerpt:        req.Excerpt,
		Route:          req.Route,
		Template:       req.Template,
		IsSystem:       req.IsSystem,
		SEOTitle:       req.SEOTitle,
		SEODescription: req.SEODescription,
	})
	if err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, dto.MapPage(page))
}

// Update godoc
//
//	@Summary		Update a page
//	@Tags			pages
//	@Security		ApiKeyAuth
//	@Accept			json
//	@Produce		json
//	@Param			id		path		int						true	"Content ID"
//	@Param			body	body		dto.UpdatePageRequest	true	"Updated page data"
//	@Success		200		{object}	dto.PageResponse
//	@Failure		400		{object}	dto.ErrorResponse
//	@Failure		404		{object}	dto.ErrorResponse
//	@Router			/pages/{id} [put]
func (h *PageHandler) Update(c *gin.Context) {
	id, err := parseUintParam(c, "id")
	if err != nil {
		return
	}
	var req dto.UpdatePageRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, dto.ErrorResponse{Error: err.Error()})
		return
	}

	page, err := h.svc.Update(c.Request.Context(), id, service.UpdatePageInput{
		Title:          req.Title,
		Body:           req.Body,
		Excerpt:        req.Excerpt,
		Route:          req.Route,
		Template:       req.Template,
		SEOTitle:       req.SEOTitle,
		SEODescription: req.SEODescription,
	})
	if err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, dto.MapPage(page))
}

// Delete godoc
//
//	@Summary		Delete a page
//	@Tags			pages
//	@Security		ApiKeyAuth
//	@Param			id	path	int	true	"Content ID"
//	@Success		204	"No Content"
//	@Failure		404	{object}	dto.ErrorResponse
//	@Router			/pages/{id} [delete]
func (h *PageHandler) Delete(c *gin.Context) {
	id, err := parseUintParam(c, "id")
	if err != nil {
		return
	}
	if err := h.svc.Delete(c.Request.Context(), id); err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.Status(http.StatusNoContent)
}

func parseUintParam(c *gin.Context, name string) (uint, error) {
	v, err := strconv.ParseUint(c.Param(name), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, dto.ErrorResponse{Error: "invalid " + name})
	}
	return uint(v), err
}
