package handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	permission_domain "github.com/mtafakori/persona/internal/permission/domain"
	"github.com/mtafakori/persona/internal/role/domain"
	"github.com/mtafakori/persona/internal/role/repository"
	"github.com/mtafakori/persona/internal/role/service"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/audit"
	"github.com/mtafakori/persona/shared/ctxkey"
	"github.com/mtafakori/persona/shared/pagination"
)

// RoleResponse is the API representation of a role.
type RoleResponse struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Slug        string    `json:"slug"`
	Description string    `json:"description"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

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

func toRoleResponse(r *domain.Role) RoleResponse {
	return RoleResponse{
		ID:          r.ID,
		Name:        r.Name,
		Slug:        r.Slug,
		Description: r.Description,
		CreatedAt:   r.CreatedAt,
		UpdatedAt:   r.UpdatedAt,
	}
}

func toPermissionResponse(p permission_domain.Permission) PermissionResponse {
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

// Handler handles HTTP requests for role resources.
type Handler struct {
	svc   *service.Service
	audit *audit.Logger
}

// New returns a new role Handler.
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

// RegisterRoutes registers all role routes on the provided router group.
func (h *Handler) RegisterRoutes(rg *gin.RouterGroup) {
	g := rg.Group("/roles")
	g.POST("", h.Create)
	g.GET("", h.List)
	g.GET("/:id", h.Get)
	g.PUT("/:id", h.Update)
	g.DELETE("/:id", h.Delete)
	g.POST("/:id/permissions", h.AssignPermission)
	g.DELETE("/:id/permissions/:permissionId", h.RemovePermission)
	g.GET("/:id/permissions", h.ListPermissions)
}

type createRequest struct {
	Name        string `json:"name"`
	Slug        string `json:"slug"`
	Description string `json:"description"`
}

func (h *Handler) Create(c *gin.Context) {
	var req createRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}
	r, err := h.svc.Create(c.Request.Context(), service.CreateInput{
		Name:        req.Name,
		Slug:        req.Slug,
		Description: req.Description,
	})
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.RoleCreated(r.ID, r.Name, h.actor(c), h.traceID(c), h.requestID(c))
	c.JSON(http.StatusCreated, toRoleResponse(r))
}

func (h *Handler) List(c *gin.Context) {
	page := pagination.FromQuery(c)
	result, err := h.svc.List(c.Request.Context(), page)
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	items := make([]RoleResponse, len(result.Items))
	for i, r := range result.Items {
		items[i] = toRoleResponse(&r)
	}
	c.JSON(http.StatusOK, pagination.Result[RoleResponse]{
		Items:      items,
		NextCursor: result.NextCursor,
		Total:      result.Total,
	})
}

func (h *Handler) Get(c *gin.Context) {
	r, err := h.svc.GetByID(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	c.JSON(http.StatusOK, toRoleResponse(r))
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
	r, err := h.svc.Update(c.Request.Context(), c.Param("id"), repository.UpdateFields{
		Name:        req.Name,
		Description: req.Description,
	})
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.RoleUpdated(r.ID, h.actor(c), h.traceID(c), h.requestID(c))
	c.JSON(http.StatusOK, toRoleResponse(r))
}

func (h *Handler) Delete(c *gin.Context) {
	id := c.Param("id")
	if err := h.svc.Delete(c.Request.Context(), id); err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.RoleDeleted(id, h.actor(c), h.traceID(c), h.requestID(c))
	c.Status(http.StatusNoContent)
}

type assignPermissionRequest struct {
	PermissionID string `json:"permission_id"`
}

func (h *Handler) AssignPermission(c *gin.Context) {
	var req assignPermissionRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.PermissionID == "" {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}
	roleID := c.Param("id")
	if err := h.svc.AssignPermission(c.Request.Context(), roleID, req.PermissionID); err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.PermissionAssignedToRole(roleID, req.PermissionID, h.actor(c), h.traceID(c), h.requestID(c))
	c.Status(http.StatusNoContent)
}

func (h *Handler) RemovePermission(c *gin.Context) {
	roleID := c.Param("id")
	permID := c.Param("permissionId")
	if err := h.svc.RemovePermission(c.Request.Context(), roleID, permID); err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.PermissionRevokedFromRole(roleID, permID, h.actor(c), h.traceID(c), h.requestID(c))
	c.Status(http.StatusNoContent)
}

func (h *Handler) ListPermissions(c *gin.Context) {
	perms, err := h.svc.ListPermissions(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	resp := make([]PermissionResponse, len(perms))
	for i, p := range perms {
		resp[i] = toPermissionResponse(p)
	}
	c.JSON(http.StatusOK, gin.H{"items": resp})
}
