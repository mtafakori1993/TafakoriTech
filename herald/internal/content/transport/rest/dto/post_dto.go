package dto

import (
	"time"

	"github.com/mtafakori/herald/internal/content/domain"
)

type TagResponse struct {
	ID   uint   `json:"id"`
	Name string `json:"name"`
	Slug string `json:"slug"`
}

type CategoryResponse struct {
	ID          uint   `json:"id"`
	Name        string `json:"name"`
	Slug        string `json:"slug"`
	Description string `json:"description"`
}

type PostResponse struct {
	ID         uint              `json:"id"`
	ContentID  uint              `json:"content_id"`
	Content    ContentResponse   `json:"content"`
	CategoryID *uint             `json:"category_id"`
	Category   *CategoryResponse `json:"category,omitempty"`
	Tags       []TagResponse     `json:"tags"`
}

type CreatePostRequest struct {
	Title          string   `json:"title"           binding:"required,min=1,max=500" example:"My First Post"`
	Slug           string   `json:"slug"                                              example:"my-first-post"`
	Body           string   `json:"body"                                              example:"Post content..."`
	Excerpt        string   `json:"excerpt"                                           example:"Short summary"`
	CategoryID     *uint    `json:"category_id"                                       example:"1"`
	Tags           []string `json:"tags"                                              example:"[\"go\",\"backend\"]"`
	SEOTitle       string   `json:"seo_title"                                         example:"My First Post | Blog"`
	SEODescription string   `json:"seo_description"                                   example:"A great post about Go"`
}

type UpdatePostRequest struct {
	Title          string   `json:"title"           binding:"required,min=1,max=500" example:"Updated Post Title"`
	Body           string   `json:"body"                                              example:"Updated content..."`
	Excerpt        string   `json:"excerpt"                                           example:"Short summary"`
	Tags           []string `json:"tags"                                              example:"[\"go\",\"backend\"]"`
	SEOTitle       string   `json:"seo_title"                                         example:"Updated Post | Blog"`
	SEODescription string   `json:"seo_description"                                   example:"Updated description"`
}

func MapPost(p *domain.Post) PostResponse {
	var pub *time.Time
	if p.Content.PublishedAt != nil {
		t := *p.Content.PublishedAt
		pub = &t
	}
	r := PostResponse{
		ID:         p.ID,
		ContentID:  p.ContentID,
		CategoryID: p.CategoryID,
		Tags:       make([]TagResponse, 0, len(p.Tags)),
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
	if p.Category != nil {
		c := CategoryResponse{
			ID:          p.Category.ID,
			Name:        p.Category.Name,
			Slug:        p.Category.Slug,
			Description: p.Category.Description,
		}
		r.Category = &c
	}
	for _, t := range p.Tags {
		r.Tags = append(r.Tags, TagResponse{ID: t.ID, Name: t.Name, Slug: t.Slug})
	}
	return r
}
