package domain

// Article represents a long-form editorial piece.
type Article struct {
	ID        uint    `gorm:"primaryKey;autoIncrement" json:"id"`
	ContentID uint    `gorm:"uniqueIndex;not null"     json:"content_id"`
	Content   Content `gorm:"foreignKey:ContentID"     json:"content"`
	ReadTime  int     `gorm:"not null;default:0"       json:"read_time"`
	Tags      []Tag   `gorm:"many2many:article_tags;"  json:"tags,omitempty"`
}

func (Article) TableName() string { return "articles" }
