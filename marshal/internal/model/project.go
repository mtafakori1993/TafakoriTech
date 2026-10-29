package model

import "time"

// Project is the full project record returned to clients.
type Project struct {
	ID          int64     `json:"id"          example:"1"`
	Name        string    `json:"name"        example:"My Project"`
	Description string    `json:"description" example:"A short description"`
	Color       string    `json:"color"       example:"#6750A4"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

// CreateProjectRequest is the body for POST /api/v1/projects.
type CreateProjectRequest struct {
	Name        string `json:"name"        binding:"required,min=1,max=100" example:"My Project"`
	Description string `json:"description" example:"A short description"`
	Color       string `json:"color"       example:"#6750A4"`
}

// UpdateProjectRequest is the body for PUT /api/v1/projects/:id.
type UpdateProjectRequest struct {
	Name        string `json:"name"        binding:"required,min=1,max=100" example:"Updated Name"`
	Description string `json:"description" example:"Updated description"`
	Color       string `json:"color"       example:"#FF6B6B"`
}
