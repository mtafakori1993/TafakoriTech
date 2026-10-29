package domain

import "time"

type ContentType string
type ContentStatus string

const (
	ContentTypePage    ContentType = "page"
	ContentTypePost    ContentType = "post"
	ContentTypeArticle ContentType = "article"
)

const (
	StatusDraft     ContentStatus = "draft"
	StatusPublished ContentStatus = "published"
)

// Content is the base entity shared by all content types.
type Content struct {
	ID             uint          `gorm:"primaryKey;autoIncrement"                  json:"id"`
	Type           ContentType   `gorm:"type:varchar(20);not null;index"           json:"type"`
	Title          string        `gorm:"not null;size:500"                         json:"title"`
	Slug           string        `gorm:"uniqueIndex;not null;size:500"             json:"slug"`
	Body           string        `gorm:"type:text"                                 json:"body"`
	Excerpt        string        `gorm:"type:text"                                 json:"excerpt"`
	Status         ContentStatus `gorm:"type:varchar(20);not null;default:'draft'" json:"status"`
	SEOTitle       string        `gorm:"size:500"                                  json:"seo_title"`
	SEODescription string        `gorm:"type:text"                                 json:"seo_description"`
	AuthorID       uint          `gorm:"not null;default:1;index"                  json:"author_id"`
	PublishedAt    *time.Time    `gorm:"index"                                     json:"published_at"`
	CreatedAt      time.Time     `                                                 json:"created_at"`
	UpdatedAt      time.Time     `                                                 json:"updated_at"`
}

func (Content) TableName() string { return "contents" }
