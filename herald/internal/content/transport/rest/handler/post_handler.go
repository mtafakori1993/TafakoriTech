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

type PostHandler struct {
	svc service.PostService
	log *zap.Logger
}

func NewPostHandler(svc service.PostService, log *zap.Logger) *PostHandler {
	return &PostHandler{svc: svc, log: log}
}

// List godoc
//
//	@Summary		List published posts
//	@Tags			posts
//	@Produce		json
//	@Param			page	query		int	false	"Page number"
//	@Param			limit	query		int	false	"Items per page"
//	@Success		200		{object}	pagination.Response[dto.PostResponse]
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/posts [get]
func (h *PostHandler) List(c *gin.Context) {
	p := pagination.FromQuery(c)
	posts, total, err := h.svc.List(c.Request.Context(), p)
	if err != nil {
		c.JSON(http.StatusInternalServerError, dto.ErrorResponse{Error: err.Error()})
		return
	}
	mapped := make([]dto.PostResponse, 0, len(posts))
	for _, post := range posts {
		mapped = append(mapped, dto.MapPost(post))
	}
	c.JSON(http.StatusOK, pagination.NewResponse(mapped, total, p))
}

// AdminList godoc
//
//	@Summary		List all posts (admin, all statuses)
//	@Tags			posts
//	@Security		ApiKeyAuth
//	@Produce		json
//	@Param			page	query		int	false	"Page number"
//	@Param			limit	query		int	false	"Items per page"
//	@Success		200		{object}	pagination.Response[dto.PostResponse]
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/admin/posts [get]
func (h *PostHandler) AdminList(c *gin.Context) {
	p := pagination.FromQuery(c)
	posts, total, err := h.svc.ListAdmin(c.Request.Context(), p)
	if err != nil {
		c.JSON(http.StatusInternalServerError, dto.ErrorResponse{Error: err.Error()})
		return
	}
	mapped := make([]dto.PostResponse, 0, len(posts))
	for _, post := range posts {
		mapped = append(mapped, dto.MapPost(post))
	}
	c.JSON(http.StatusOK, pagination.NewResponse(mapped, total, p))
}

// Update godoc
//
//	@Summary		Update a post
//	@Tags			posts
//	@Security		ApiKeyAuth
//	@Accept			json
//	@Produce		json
//	@Param			id		path		int					true	"Content ID"
//	@Param			body	body		dto.UpdatePostRequest	true	"Updated post data"
//	@Success		200		{object}	dto.PostResponse
//	@Failure		400		{object}	dto.ErrorResponse
//	@Failure		404		{object}	dto.ErrorResponse
//	@Router			/posts/{id} [put]
func (h *PostHandler) Update(c *gin.Context) {
	id, err := parseUintParam(c, "id")
	if err != nil {
		return
	}
	var req dto.UpdatePostRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, dto.ErrorResponse{Error: err.Error()})
		return
	}
	post, err := h.svc.Update(c.Request.Context(), id, service.UpdatePostInput{
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
	c.JSON(http.StatusOK, dto.MapPost(post))
}

// Delete godoc
//
//	@Summary		Delete a post
//	@Tags			posts
//	@Security		ApiKeyAuth
//	@Param			id	path	int	true	"Content ID"
//	@Success		204	"No Content"
//	@Failure		404	{object}	dto.ErrorResponse
//	@Router			/posts/{id} [delete]
func (h *PostHandler) Delete(c *gin.Context) {
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

// GetBySlug godoc
//
//	@Summary		Get post by slug
//	@Tags			posts
//	@Produce		json
//	@Param			slug	path		string	true	"Post slug"
//	@Success		200		{object}	dto.PostResponse
//	@Failure		404		{object}	dto.ErrorResponse
//	@Router			/posts/{slug} [get]
func (h *PostHandler) GetBySlug(c *gin.Context) {
	post, err := h.svc.GetBySlug(c.Request.Context(), c.Param("slug"))
	if err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusOK, dto.MapPost(post))
}

// Create godoc
//
//	@Summary		Create a post
//	@Tags			posts
//	@Security		ApiKeyAuth
//	@Accept			json
//	@Produce		json
//	@Param			body	body		dto.CreatePostRequest	true	"Post data"
//	@Success		201		{object}	dto.PostResponse
//	@Failure		400		{object}	dto.ErrorResponse
//	@Failure		500		{object}	dto.ErrorResponse
//	@Router			/posts [post]
func (h *PostHandler) Create(c *gin.Context) {
	var req dto.CreatePostRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, dto.ErrorResponse{Error: err.Error()})
		return
	}

	post, err := h.svc.Create(c.Request.Context(), service.CreatePostInput{
		Title:          req.Title,
		Slug:           req.Slug,
		Body:           req.Body,
		Excerpt:        req.Excerpt,
		CategoryID:     req.CategoryID,
		Tags:           req.Tags,
		SEOTitle:       req.SEOTitle,
		SEODescription: req.SEODescription,
	})
	if err != nil {
		c.JSON(apperror.HTTPCode(err), dto.ErrorResponse{Error: err.Error()})
		return
	}
	c.JSON(http.StatusCreated, dto.MapPost(post))
}
