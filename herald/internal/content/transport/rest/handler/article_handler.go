package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/mtafakori/herald/internal/content/service"
	"github.com/mtafakori/herald/internal/content/transport/rest/dto"
	"github.com/mtafakori/herald/internal/shared/apperror"
	"github.com/mtafakori/herald/internal/shared/pagination"
)

type ArticleHandler struct {
	svc service.ArticleService
	log *zap.Logger
}

func NewArticleHandler(svc service.ArticleService, log *zap.Logger) *ArticleHandler {
	return &ArticleHandler{svc: svc, log: log}
}

// List godoc
//
//	@Summary		List published articles
//	@Tags			articles
//	@Produce		json
//	@Param			page	query		int	false	"Page number"
//	@Param			limit	query		int	false	"Items per page"
//	@Success		200		{object}	pagination.Response[dto.ArticleResponse]
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/articles [get]
func (h *ArticleHandler) List(c *gin.Context) {
	p := pagination.FromQuery(c)
	articles, total, err := h.svc.List(c.Request.Context(), p)
	if err != nil {
		c.JSON(http.StatusInternalServerError, dto.ErrorResponse{Error: err.Error()})
		return
	}
	mapped := make([]dto.ArticleResponse, 0, len(articles))
	for _, a := range articles {
		mapped = append(mapped, dto.MapArticle(a))
	}
	c.JSON(http.StatusOK, pagination.NewResponse(mapped, total, p))
}

// GetBySlug godoc
//
//	@Summary		Get article by slug
//	@Tags			articles
//	@Produce		json
//	@Param			slug	path		string	true	"Article slug"
//	@Success		200		{object}	dto.ArticleResponse
//	@Failure		404		{object}	dto.ErrorResponse
//	@Router			/articles/{slug} [get]
func (h *ArticleHandler) GetBySlug(c *gin.Context) {
	article, err := h.svc.GetBySlug(c.Request.Context(), c.Param("slug"))
	if err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, dto.MapArticle(article))
}

// AdminList godoc
//
//	@Summary		List all articles (admin, all statuses)
//	@Tags			articles
//	@Security		ApiKeyAuth
//	@Produce		json
//	@Param			page	query		int	false	"Page number"
//	@Param			limit	query		int	false	"Items per page"
//	@Success		200		{object}	pagination.Response[dto.ArticleResponse]
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/admin/articles [get]
func (h *ArticleHandler) AdminList(c *gin.Context) {
	p := pagination.FromQuery(c)
	articles, total, err := h.svc.ListAdmin(c.Request.Context(), p)
	if err != nil {
		c.JSON(http.StatusInternalServerError, dto.ErrorResponse{Error: err.Error()})
		return
	}
	mapped := make([]dto.ArticleResponse, 0, len(articles))
	for _, a := range articles {
		mapped = append(mapped, dto.MapArticle(a))
	}
	c.JSON(http.StatusOK, pagination.NewResponse(mapped, total, p))
}

// Update godoc
//
//	@Summary		Update an article
//	@Tags			articles
//	@Security		ApiKeyAuth
//	@Accept			json
//	@Produce		json
//	@Param			id		path		int						true	"Content ID"
//	@Param			body	body		dto.UpdateArticleRequest	true	"Updated article data"
//	@Success		200		{object}	dto.ArticleResponse
//	@Failure		400		{object}	dto.ErrorResponse
//	@Failure		404		{object}	dto.ErrorResponse
//	@Router			/articles/{id} [put]
func (h *ArticleHandler) Update(c *gin.Context) {
	id, err := parseUintParam(c, "id")
	if err != nil {
		return
	}
	var req dto.UpdateArticleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, dto.ErrorResponse{Error: err.Error()})
		return
	}
	article, err := h.svc.Update(c.Request.Context(), id, service.UpdateArticleInput{
		Title:          req.Title,
		Body:           req.Body,
		Excerpt:        req.Excerpt,
		Tags:           req.Tags,
		SEOTitle:       req.SEOTitle,
		SEODescription: req.SEODescription,
	})
	if err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, dto.MapArticle(article))
}

// Delete godoc
//
//	@Summary		Delete an article
//	@Tags			articles
//	@Security		ApiKeyAuth
//	@Param			id	path	int	true	"Content ID"
//	@Success		204	"No Content"
//	@Failure		404	{object}	dto.ErrorResponse
//	@Router			/articles/{id} [delete]
func (h *ArticleHandler) Delete(c *gin.Context) {
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

// Create godoc
//
//	@Summary		Create an article
//	@Tags			articles
//	@Security		ApiKeyAuth
//	@Accept			json
//	@Produce		json
//	@Param			body	body		dto.CreateArticleRequest	true	"Article data"
//	@Success		201		{object}	dto.ArticleResponse
//	@Failure		400		{object}	dto.ErrorResponse
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/articles [post]
func (h *ArticleHandler) Create(c *gin.Context) {
	var req dto.CreateArticleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, dto.ErrorResponse{Error: err.Error()})
		return
	}

	article, err := h.svc.Create(c.Request.Context(), service.CreateArticleInput{
		Title:          req.Title,
		Slug:           req.Slug,
		Body:           req.Body,
		Excerpt:        req.Excerpt,
		Tags:           req.Tags,
		SEOTitle:       req.SEOTitle,
		SEODescription: req.SEODescription,
	})
	if err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, dto.MapArticle(article))
}
