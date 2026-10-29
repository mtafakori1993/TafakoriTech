package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/persona/internal/access/service"
	"github.com/mtafakori/persona/shared/apperror"
)

// UserContextResponse is the authorization snapshot returned to Sentinel.
type UserContextResponse struct {
	UserID       string   `json:"user_id"`
	Roles        []string `json:"roles"`
	Permissions  []string `json:"permissions"`
	Status       string   `json:"status"`
	AuthzVersion int64    `json:"authz_version"`
}

// Handler serves internal authorization context endpoints.
type Handler struct {
	svc *service.Service
}

// New returns a new access Handler.
func New(svc *service.Service) *Handler {
	return &Handler{svc: svc}
}

// RegisterInternalRoutes mounts routes under the /internal group.
func (h *Handler) RegisterInternalRoutes(rg *gin.RouterGroup) {
	rg.GET("/users/:id/context", h.GetUserContext)
}

// GetUserContext returns the full RBAC context for a user.
// Used by Sentinel during JWT issuance to embed roles and permissions into the token.
func (h *Handler) GetUserContext(c *gin.Context) {
	uc, err := h.svc.GetUserContext(c.Request.Context(), c.Param("id"))
	if err != nil {
		apperror.Respond(c, err)
		return
	}

	roles := uc.Roles
	if roles == nil {
		roles = []string{}
	}
	perms := uc.Permissions
	if perms == nil {
		perms = []string{}
	}

	c.JSON(http.StatusOK, UserContextResponse{
		UserID:       uc.UserID,
		Roles:        roles,
		Permissions:  perms,
		Status:       string(uc.Status),
		AuthzVersion: uc.AuthzVersion,
	})
}
