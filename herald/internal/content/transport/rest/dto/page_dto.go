package dto

import (
	"time"

	"github.com/mtafakori/herald/internal/content/domain"
)

type PageResponse struct {
	ID       uint            `json:"id"`
	Content  ContentResponse `json:"content"`
	Route    string          `json:"route"`
	Template string          `json:"template"`
	IsSystem bool            `json:"is_system"`
}

type CreatePageRequest struct {
	Title          string `json:"title"           binding:"required,min=1,max=500" example:"About Us"`
	Slug           string `json:"slug"                                              example:"about-us"`
	Body           string `json:"body"                                              example:"<p>We are...</p>"`
	Excerpt        string `json:"excerpt"                                           example:"Short description"`
	Route          string `json:"route"           binding:"required"                example:"/about"`
	Template       string `json:"template"                                          example:"default"`
	IsSystem       bool   `json:"is_system"                                         example:"false"`
	SEOTitle       string `json:"seo_title"                                         example:"About Us | Herald"`
	SEODescription string `json:"seo_description"                                   example:"Learn about us"`
}

type UpdatePageRequest struct {
	Title          string `json:"title"           binding:"required,min=1,max=500" example:"About Us"`
	Body           string `json:"body"                                              example:"<p>We are...</p>"`
	Excerpt        string `json:"excerpt"                                           example:"Short description"`
	Route          string `json:"route"           binding:"required"                example:"/about"`
	Template       string `json:"template"                                          example:"default"`
	SEOTitle       string `json:"seo_title"                                         example:"About Us | Herald"`
	SEODescription string `json:"seo_description"                                   example:"Learn about us"`
}

func MapPage(p *domain.Page) PageResponse {
	var pub *time.Time
	if p.Content.PublishedAt != nil {
		t := *p.Content.PublishedAt
		pub = &t
	}
	return PageResponse{
		ID:    p.ContentID,
		Route: p.Route,
		Template:  p.Template,
		IsSystem:  p.IsSystem,
		Content: ContentResponse{
			ID:             p.Content.ID,
			Type:           string(p.Content.Type),
			Title:          p.Content.Title,
			Slug:           p.Content.Slug,
			Body:           p.Content.Body,
			Excerpt:        p.Content.Excerpt,
			Status:         string(p.Content.Status),
			SEOTitle:       p.Content.SEOTitle,
			SEODescription: p.Content.SEODescription,
			AuthorID:       p.Content.AuthorID,
			PublishedAt:    pub,
			CreatedAt:      p.Content.CreatedAt,
			UpdatedAt:      p.Content.UpdatedAt,
		},
	}
}
