package dto

import "time"

type ErrorResponse struct {
	Error string `json:"error"`
}

type ContentResponse struct {
	ID             uint       `json:"id"`
	Type           string     `json:"type"`
	Title          string     `json:"title"`
	Slug           string     `json:"slug"`
	Body           string     `json:"body"`
	Excerpt        string     `json:"excerpt"`
	Status         string     `json:"status"`
	SEOTitle       string     `json:"seo_title"`
	SEODescription string     `json:"seo_description"`
	AuthorID       uint       `json:"author_id"`
	PublishedAt    *time.Time `json:"published_at"`
	CreatedAt      time.Time  `json:"created_at"`
	UpdatedAt      time.Time  `json:"updated_at"`
}
