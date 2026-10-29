package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/mtafakori/marshal/internal/db"
	"github.com/mtafakori/marshal/internal/model"
)

type Projects struct {
	store *db.Store
}

func NewProjects(store *db.Store) *Projects {
	return &Projects{store: store}
}

// List godoc
//
//	@Summary		List all projects
//	@Tags			projects
//	@Security		ApiKeyAuth
//	@Produce		json
//	@Success		200	{array}		model.Project
//	@Failure		500	{object}	map[string]string
//	@Router			/api/v1/projects [get]
func (h *Projects) List(c *gin.Context) {
	projects, err := h.store.ListProjects(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if projects == nil {
		projects = []model.Project{}
	}
	c.JSON(http.StatusOK, projects)
}

// Get godoc
//
//	@Summary		Get a project by ID
//	@Tags			projects
//	@Security		ApiKeyAuth
//	@Param			id	path		int	true	"Project ID"
//	@Produce		json
//	@Success		200	{object}	model.Project
//	@Failure		404	{object}	map[string]string
//	@Failure		500	{object}	map[string]string
//	@Router			/api/v1/projects/{id} [get]
func (h *Projects) Get(c *gin.Context) {
	id, err := parseID(c)
	if err != nil {
		return
	}
	project, err := h.store.GetProject(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if project == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "project not found"})
		return
	}
	c.JSON(http.StatusOK, project)
}

// Create godoc
//
//	@Summary		Create a project
//	@Tags			projects
//	@Security		ApiKeyAuth
//	@Accept			json
//	@Produce		json
//	@Param			body	body		model.CreateProjectRequest	true	"Project data"
//	@Success		201		{object}	model.Project
//	@Failure		400		{object}	map[string]string
//	@Failure		500		{object}	map[string]string
//	@Router			/api/v1/projects [post]
func (h *Projects) Create(c *gin.Context) {
	var req model.CreateProjectRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	project, err := h.store.CreateProject(c.Request.Context(), req.Name, req.Description, req.Color)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, project)
}

// Update godoc
//
//	@Summary		Update a project
//	@Tags			projects
//	@Security		ApiKeyAuth
//	@Accept			json
//	@Produce		json
//	@Param			id		path		int							true	"Project ID"
//	@Param			body	body		model.UpdateProjectRequest	true	"Updated project data"
//	@Success		200		{object}	model.Project
//	@Failure		400		{object}	map[string]string
//	@Failure		404		{object}	map[string]string
//	@Failure		500		{object}	map[string]string
//	@Router			/api/v1/projects/{id} [put]
func (h *Projects) Update(c *gin.Context) {
	id, err := parseID(c)
	if err != nil {
		return
	}
	var req model.UpdateProjectRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	project, err := h.store.UpdateProject(c.Request.Context(), id, req.Name, req.Description, req.Color)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if project == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "project not found"})
		return
	}
	c.JSON(http.StatusOK, project)
}

// Delete godoc
//
//	@Summary		Delete a project
//	@Tags			projects
//	@Security		ApiKeyAuth
//	@Param			id	path	int	true	"Project ID"
//	@Success		204	"No Content"
//	@Failure		404	{object}	map[string]string
//	@Failure		500	{object}	map[string]string
//	@Router			/api/v1/projects/{id} [delete]
func (h *Projects) Delete(c *gin.Context) {
	id, err := parseID(c)
	if err != nil {
		return
	}
	deleted, err := h.store.DeleteProject(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if !deleted {
		c.JSON(http.StatusNotFound, gin.H{"error": "project not found"})
		return
	}
	c.Status(http.StatusNoContent)
}

func parseID(c *gin.Context) (int64, error) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
	}
	return id, err
}
