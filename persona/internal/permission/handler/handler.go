package handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/persona/internal/permission/domain"
	"github.com/mtafakori/persona/internal/permission/repository"
	"github.com/mtafakori/persona/internal/permission/service"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/audit"
	"github.com/mtafakori/persona/shared/ctxkey"
	"github.com/mtafakori/persona/shared/pagination"
)

// PermissionResponse is the API representation of a permission.
type PermissionResponse struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Slug        string    `json:"slug"`
	Resource    string    `json:"resource"`
	Action      string    `json:"action"`
	Description string    `json:"description"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

func toPermissionResponse(p *domain.Permission) PermissionResponse {
	return PermissionResponse{
		ID:          p.ID,
		Name:        p.Name,
		Slug:        p.Slug,
		Resource:    p.Resource,
		Action:      p.Action,
		Description: p.Description,
		CreatedAt:   p.CreatedAt,
		UpdatedAt:   p.UpdatedAt,
	}
}

// Handler handles HTTP requests for permission resources.
type Handler struct {
	svc   *service.Service
	audit *audit.Logger
}

// New returns a new permission Handler.
func New(svc *service.Service, auditLog *audit.Logger) *Handler {
	return &Handler{svc: svc, audit: auditLog}
}

func (h *Handler) actor(c *gin.Context) string {
	return c.GetString(string(ctxkey.AuthUserIDKey))
}

func (h *Handler) traceID(c *gin.Context) string {
	return c.GetString(string(ctxkey.TraceIDKey))
}

func (h *Handler) requestID(c *gin.Context) string {
	return c.GetString(string(ctxkey.RequestIDKey))
}

// RegisterRoutes registers all permission routes on the provided router group.
func (h *Handler) RegisterRoutes(rg *gin.RouterGroup) {
	g := rg.Group("/permissions")
	g.POST("", h.Create)
	g.GET("", h.List)
	g.GET("/:id", h.Get)
	g.PUT("/:id", h.Update)
	g.DELETE("/:id", h.Delete)
}

type createRequest struct {
	Name        string `json:"name"`
	Slug        string `json:"slug"`
	Resource    string `json:"resource"`
	Action      string `json:"action"`
	Description string `json:"description"`
}

func (h *Handler) Create(c *gin.Context) {
	var req createRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}
	p, err := h.svc.Create(c.Request.Context(), service.CreateInput{
		Name:        req.Name,
		Slug:        req.Slug,
		Resource:    req.Resource,
		Action:      req.Action,
		Description: req.Description,
	})
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.PermissionCreated(p.ID, p.Name, h.actor(c), h.traceID(c), h.requestID(c))
	c.JSON(http.StatusCreated, toPermissionResponse(p))
}

func (h *Handler) List(c *gin.Context) {
	page := pagination.FromQuery(c)
	result, err := h.svc.List(c.Request.Context(), page)
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	items := make([]PermissionResponse, len(result.Items))
	for i, p := range result.Items {
		items[i] = toPermissionResponse(&p)
	}
	c.JSON(http.StatusOK, pagination.Result[PermissionResponse]{
		Items:      items,
		NextCursor: result.NextCursor,
		Total:      result.Total,
	})
}

func (h *Handler) Get(c *gin.Context) {
	p, err := h.svc.GetByID(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	c.JSON(http.StatusOK, toPermissionResponse(p))
}

type updateRequest struct {
	Name        *string `json:"name"`
	Description *string `json:"description"`
}

func (h *Handler) Update(c *gin.Context) {
	var req updateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}
	p, err := h.svc.Update(c.Request.Context(), c.Param("id"), repository.UpdateFields{
		Name:        req.Name,
		Description: req.Description,
	})
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.PermissionUpdated(p.ID, h.actor(c), h.traceID(c), h.requestID(c))
	c.JSON(http.StatusOK, toPermissionResponse(p))
}

func (h *Handler) Delete(c *gin.Context) {
	id := c.Param("id")
	if err := h.svc.Delete(c.Request.Context(), id); err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.PermissionDeleted(id, h.actor(c), h.traceID(c), h.requestID(c))
	c.Status(http.StatusNoContent)
}
