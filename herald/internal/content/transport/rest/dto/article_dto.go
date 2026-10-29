package dto

import (
	"time"

	"github.com/mtafakori/herald/internal/content/domain"
)

type ArticleResponse struct {
	ID        uint            `json:"id"`
	ContentID uint            `json:"content_id"`
	Content   ContentResponse `json:"content"`
	ReadTime  int             `json:"read_time"`
	Tags      []TagResponse   `json:"tags"`
}

type CreateArticleRequest struct {
	Title          string   `json:"title"           binding:"required,min=1,max=500" example:"Deep Dive into Go"`
	Slug           string   `json:"slug"                                              example:"deep-dive-into-go"`
	Body           string   `json:"body"                                              example:"Article content..."`
	Excerpt        string   `json:"excerpt"                                           example:"Short summary"`
	Tags           []string `json:"tags"                                              example:"[\"go\",\"performance\"]"`
	SEOTitle       string   `json:"seo_title"                                         example:"Deep Dive into Go | Articles"`
	SEODescription string   `json:"seo_description"                                   example:"A comprehensive Go guide"`
}

type UpdateArticleRequest struct {
	Title          string   `json:"title"           binding:"required,min=1,max=500" example:"Updated Article Title"`
	Body           string   `json:"body"                                              example:"Updated content..."`
	Excerpt        string   `json:"excerpt"                                           example:"Short summary"`
	Tags           []string `json:"tags"                                              example:"[\"go\",\"performance\"]"`
	SEOTitle       string   `json:"seo_title"                                         example:"Updated Article | Articles"`
	SEODescription string   `json:"seo_description"                                   example:"Updated description"`
}

func MapArticle(a *domain.Article) ArticleResponse {
	var pub *time.Time
	if a.Content.PublishedAt != nil {
		t := *a.Content.PublishedAt
		pub = &t
	}
	r := ArticleResponse{
		ID:        a.ID,
		ContentID: a.ContentID,
		ReadTime:  a.ReadTime,
		Tags:      make([]TagResponse, 0, len(a.Tags)),
		Content: ContentResponse{
			ID:             a.Content.ID,
			Type:           string(a.Content.Type),
			Title:          a.Content.Title,
			Slug:           a.Content.Slug,
			Body:           a.Content.Body,
			Excerpt:        a.Content.Excerpt,
			Status:         string(a.Content.Status),
			SEOTitle:       a.Content.SEOTitle,
			SEODescription: a.Content.SEODescription,
			AuthorID:       a.Content.AuthorID,
			PublishedAt:    pub,
			CreatedAt:      a.Content.CreatedAt,
			UpdatedAt:      a.Content.UpdatedAt,
		},
	}
	for _, t := range a.Tags {
		r.Tags = append(r.Tags, TagResponse{ID: t.ID, Name: t.Name, Slug: t.Slug})
	}
	return r
}
