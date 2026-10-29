package pagination

import (
	"strconv"

	"github.com/gin-gonic/gin"
)

// Page holds cursor-based pagination parameters parsed from query strings.
type Page struct {
	Limit  int
	Cursor string // UUID string, empty = first page
	Search string
	Status string
	RoleID string
	TeamID string
}

// FromQuery parses pagination parameters from the gin request query string.
// Limit is clamped between 1 and 100, defaulting to 20.
func FromQuery(c *gin.Context) Page {
	limit := 20
	if l := c.Query("limit"); l != "" {
		if v, err := strconv.Atoi(l); err == nil {
			limit = v
		}
	}
	if limit < 1 {
		limit = 1
	}
	if limit > 100 {
		limit = 100
	}

	return Page{
		Limit:  limit,
		Cursor: c.Query("cursor"),
		Search: c.Query("search"),
		Status: c.Query("status"),
		RoleID: c.Query("role_id"),
		TeamID: c.Query("team_id"),
	}
}

// Result is a generic paginated response container.
type Result[T any] struct {
	Items      []T    `json:"items"`
	NextCursor string `json:"next_cursor,omitempty"`
	Total      int64  `json:"total"`
}
