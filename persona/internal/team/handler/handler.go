package handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/persona/internal/team/domain"
	"github.com/mtafakori/persona/internal/team/repository"
	"github.com/mtafakori/persona/internal/team/service"
	user_domain "github.com/mtafakori/persona/internal/user/domain"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

// TeamResponse is the API representation of a team.
type TeamResponse struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Description string    `json:"description"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

// UserResponse is the API representation of a user.
type UserResponse struct {
	ID        string    `json:"id"`
	FirstName string    `json:"first_name"`
	LastName  string    `json:"last_name"`
	Email     string    `json:"email"`
	Phone     *string   `json:"phone,omitempty"`
	AvatarURL *string   `json:"avatar_url,omitempty"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

func toTeamResponse(t *domain.Team) TeamResponse {
	return TeamResponse{
		ID:          t.ID,
		Name:        t.Name,
		Description: t.Description,
		CreatedAt:   t.CreatedAt,
		UpdatedAt:   t.UpdatedAt,
	}
}

func toUserResponse(u user_domain.User) UserResponse {
	return UserResponse{
		ID:        u.ID,
		FirstName: u.FirstName,
		LastName:  u.LastName,
		Email:     u.Email,
		Phone:     u.Phone,
		AvatarURL: u.AvatarURL,
		Status:    string(u.Status),
		CreatedAt: u.CreatedAt,
		UpdatedAt: u.UpdatedAt,
	}
}

// Handler handles HTTP requests for team resources.
type Handler struct {
	svc *service.Service
}

// New returns a new team Handler.
func New(svc *service.Service) *Handler {
	return &Handler{svc: svc}
}

// RegisterRoutes registers all team routes on the provided router group.
func (h *Handler) RegisterRoutes(rg *gin.RouterGroup) {
	g := rg.Group("/teams")
	g.POST("", h.Create)
	g.GET("", h.List)
	g.GET("/:id", h.Get)
	g.PUT("/:id", h.Update)
	g.DELETE("/:id", h.Delete)
	g.POST("/:id/users", h.AddUser)
	g.DELETE("/:id/users/:userId", h.RemoveUser)
	g.GET("/:id/users", h.ListUsers)
}

type createRequest struct {
	Name        string `json:"name"`
	Description string `json:"description"`
}

func (h *Handler) Create(c *gin.Context) {
	var req createRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}
	t, err := h.svc.Create(c.Request.Context(), service.CreateInput{
		Name:        req.Name,
		Description: req.Description,
	})
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	c.JSON(http.StatusCreated, toTeamResponse(t))
}

func (h *Handler) List(c *gin.Context) {
	page := pagination.FromQuery(c)
	result, err := h.svc.List(c.Request.Context(), page)
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	items := make([]TeamResponse, len(result.Items))
	for i, t := range result.Items {
		items[i] = toTeamResponse(&t)
	}
	c.JSON(http.StatusOK, pagination.Result[TeamResponse]{
		Items:      items,
		NextCursor: result.NextCursor,
		Total:      result.Total,
	})
}

func (h *Handler) Get(c *gin.Context) {
	t, err := h.svc.GetByID(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	c.JSON(http.StatusOK, toTeamResponse(t))
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
	t, err := h.svc.Update(c.Request.Context(), c.Param("id"), repository.UpdateFields{
		Name:        req.Name,
		Description: req.Description,
	})
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	c.JSON(http.StatusOK, toTeamResponse(t))
}

func (h *Handler) Delete(c *gin.Context) {
	if err := h.svc.Delete(c.Request.Context(), c.Param("id")); err != nil {
		apperror.Respond(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

type addUserRequest struct {
	UserID string `json:"user_id"`
}

func (h *Handler) AddUser(c *gin.Context) {
	var req addUserRequest
	if err := c.ShouldBindJSON(&req); err != nil || req.UserID == "" {
		apperror.Respond(c, apperror.ErrInvalidInput)
		return
	}
	if err := h.svc.AddUser(c.Request.Context(), c.Param("id"), req.UserID); err != nil {
		apperror.Respond(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func (h *Handler) RemoveUser(c *gin.Context) {
	if err := h.svc.RemoveUser(c.Request.Context(), c.Param("id"), c.Param("userId")); err != nil {
		apperror.Respond(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func (h *Handler) ListUsers(c *gin.Context) {
	users, err := h.svc.ListUsers(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}
	resp := make([]UserResponse, len(users))
	for i, u := range users {
		resp[i] = toUserResponse(u)
	}
	c.JSON(http.StatusOK, gin.H{"items": resp})
}
