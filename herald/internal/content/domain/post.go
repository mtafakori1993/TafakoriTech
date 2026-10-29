package domain

// Post represents a blog post entry.
type Post struct {
	ID         uint      `gorm:"primaryKey;autoIncrement" json:"id"`
	ContentID  uint      `gorm:"uniqueIndex;not null"     json:"content_id"`
	Content    Content   `gorm:"foreignKey:ContentID"     json:"content"`
	CategoryID *uint     `gorm:"index"                    json:"category_id"`
	Category   *Category `gorm:"foreignKey:CategoryID"    json:"category,omitempty"`
	Tags       []Tag     `gorm:"many2many:post_tags;"     json:"tags,omitempty"`
}

func (Post) TableName() string { return "posts" }
