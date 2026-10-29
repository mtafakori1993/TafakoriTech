package handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	role_domain "github.com/mtafakori/persona/internal/role/domain"
	team_domain "github.com/mtafakori/persona/internal/team/domain"
	"github.com/mtafakori/persona/internal/user/domain"
	"github.com/mtafakori/persona/internal/user/repository"
	"github.com/mtafakori/persona/internal/user/service"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/audit"
	"github.com/mtafakori/persona/shared/ctxkey"
	"github.com/mtafakori/persona/shared/pagination"
)

// UserResponse is the API representation of a user (no deleted_at field).
type UserResponse struct {
	ID           string    `json:"id"`
	FirstName    string    `json:"first_name"`
	LastName     string    `json:"last_name"`
	Email        string    `json:"email"`
	Phone        *string   `json:"phone,omitempty"`
	AvatarURL    *string   `json:"avatar_url,omitempty"`
	Status       string    `json:"status"`
	AuthzVersion int64     `json:"authz_version"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

// RoleResponse is the API representation of a role.
type RoleResponse struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Slug        string    `json:"slug"`
	Description string    `json:"description"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

// TeamResponse is the API representation of a team.
type TeamResponse struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Description string    `json:"description"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

func toUserResponse(u *domain.User) UserResponse {
	return UserResponse{
		ID:           u.ID,
		FirstName:    u.FirstName,
		LastName:     u.LastName,
		Email:        u.Email,
		Phone:        u.Phone,
		AvatarURL:    u.AvatarURL,
		Status:       string(u.Status),
		AuthzVersion: u.AuthzVersion,
		CreatedAt:    u.CreatedAt,
		UpdatedAt:    u.UpdatedAt,
	}
}

func toRoleResponse(r role_domain.Role) RoleResponse {
	return RoleResponse{
		ID:          r.ID,
		Name:        r.Name,
		Slug:        r.Slug,
		Description: r.Description,
		CreatedAt:   r.CreatedAt,
		UpdatedAt:   r.UpdatedAt,
	}
}

func toTeamResponse(t team_domain.Team) TeamResponse {
	return TeamResponse{
		ID:          t.ID,
		Name:        t.Name,
		Description: t.Description,
		CreatedAt:   t.CreatedAt,
		UpdatedAt:   t.UpdatedAt,
	}
}

// Handler handles HTTP requests for user resources.
type Handler struct {
	svc   *service.Service
	audit *audit.Logger
}

// New returns a new user Handler.
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

// RegisterInternalRoutes registers internal service-to-service routes (no JWT required).
func (h *Handler) RegisterInternalRoutes(rg *gin.RouterGroup) {
	rg.GET("/users/by-email", h.GetByEmail)
}

// RegisterRoutes registers all user routes on the provided router group.
func (h *Handler) RegisterRoutes(rg *gin.RouterGroup) {
	g := rg.Group("/users")
	g.POST("", h.Create)
	g.GET("", h.List)
	g.GET("/:id", h.Get)
	g.PUT("/:id", h.Update)
	g.DELETE("/:id", h.Delete)
	g.POST("/:id/roles", h.AssignRole)
	g.DELETE("/:id/roles/:roleId", h.RemoveRole)
	g.GET("/:id/roles", h.ListRoles)
	g.GET("/:id/teams", h.ListTeams)
}

type createRequest struct {
	FirstName string  `json:"first_name"`
	LastName  string  `json:"last_name"`
	Email     string  `json:"email"`
	Phone     *string `json:"phone"`
	AvatarURL *string `json:"avatar_url"`
	Status    *string `json:"status"`
}

func (h *Handler) GetByEmail(c *gin.Context) {
	identifier := c.Query("identifier")
	if identifier == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "identifier is required"})
		return
	}
	u, err := h.svc.GetByIdentifier(c.Request.Context(), identifier)
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	if u.Status != domain.StatusActive {
		c.JSON(http.StatusForbidden, gin.H{"error": "user is inactive"})
		return
	}
	c.JSON(http.StatusOK, toUserResponse(u))
}

func (h *Handler) Create(c *gin.Context) {
	var req createRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}

	in := service.CreateInput{
		FirstName: req.FirstName,
		LastName:  req.LastName,
		Email:     req.Email,
		Phone:     req.Phone,
		AvatarURL: req.AvatarURL,
	}
	if req.Status != nil {
		s := domain.Status(*req.Status)
		in.Status = &s
	}

	u, err := h.svc.Create(c.Request.Context(), in)
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.UserCreated(u.ID, h.actor(c), h.traceID(c), h.requestID(c))
	c.JSON(http.StatusCreated, toUserResponse(u))
}

func (h *Handler) List(c *gin.Context) {
	page := pagination.FromQuery(c)
	result, err := h.svc.List(c.Request.Context(), page)
	if err != nil {
		apperror.Respond(c, err)
		return
	}

	items := make([]UserResponse, len(result.Items))
	for i, u := range result.Items {
		items[i] = toUserResponse(&u)
	}
	c.JSON(http.StatusOK, pagination.Result[UserResponse]{
		Items:      items,
		NextCursor: result.NextCursor,
		Total:      result.Total,
	})
}

func (h *Handler) Get(c *gin.Context) {
	u, err := h.svc.GetByID(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	c.JSON(http.StatusOK, toUserResponse(u))
}

type updateRequest struct {
	FirstName *string `json:"first_name"`
	LastName  *string `json:"last_name"`
	Phone     *string `json:"phone"`
	AvatarURL *string `json:"avatar_url"`
	Status    *string `json:"status"`
}

func (h *Handler) Update(c *gin.Context) {
	var req updateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}

	fields := repository.UpdateFields{
		FirstName: req.FirstName,
		LastName:  req.LastName,
		Phone:     req.Phone,
		AvatarURL: req.AvatarURL,
	}
	if req.Status != nil {
		s := domain.Status(*req.Status)
		fields.Status = &s
	}

	u, err := h.svc.Update(c.Request.Context(), c.Param("id"), fields)
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.UserUpdated(u.ID, h.actor(c), h.traceID(c), h.requestID(c))
	c.JSON(http.StatusOK, toUserResponse(u))
}

func (h *Handler) Delete(c *gin.Context) {
	id := c.Param("id")
	if err := h.svc.Delete(c.Request.Context(), id); err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.UserDeleted(id, h.actor(c), h.traceID(c), h.requestID(c))
	c.Status(http.StatusNoContent)
}

type assignRoleRequest struct {
	RoleID string `json:"role_id"`
}

func (h *Handler) AssignRole(c *gin.Context) {
	var req assignRoleRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.RoleID == "" {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}
	userID := c.Param("id")
	if err := h.svc.AssignRole(c.Request.Context(), userID, req.RoleID); err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.RoleAssignedToUser(userID, req.RoleID, h.actor(c), h.traceID(c), h.requestID(c))
	c.Status(http.StatusNoContent)
}

func (h *Handler) RemoveRole(c *gin.Context) {
	userID := c.Param("id")
	roleID := c.Param("roleId")
	if err := h.svc.RemoveRole(c.Request.Context(), userID, roleID); err != nil {
		apperror.Respond(c, err)
		return
	}
	h.audit.RoleRevokedFromUser(userID, roleID, h.actor(c), h.traceID(c), h.requestID(c))
	c.Status(http.StatusNoContent)
}

func (h *Handler) ListRoles(c *gin.Context) {
	roles, err := h.svc.ListRoles(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	resp := make([]RoleResponse, len(roles))
	for i, r := range roles {
		resp[i] = toRoleResponse(r)
	}
	c.JSON(http.StatusOK, gin.H{"items": resp})
}

func (h *Handler) ListTeams(c *gin.Context) {
	teams, err := h.svc.ListTeams(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	resp := make([]TeamResponse, len(teams))
	for i, t := range teams {
		resp[i] = toTeamResponse(t)
	}
	c.JSON(http.StatusOK, gin.H{"items": resp})
}
