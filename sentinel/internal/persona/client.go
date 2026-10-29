package persona

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
)

var (
	ErrUserNotFound = errors.New("user not found in persona")
	ErrUserInactive = errors.New("user is inactive")
)

// UserContext holds the authorization snapshot returned by Persona for JWT enrichment.
type UserContext struct {
	UserID       string   `json:"user_id"`
	Roles        []string `json:"roles"`
	Permissions  []string `json:"permissions"`
	Status       string   `json:"status"`
	AuthzVersion int64    `json:"authz_version"`
}

type Client struct {
	baseURL string
	apiKey  string
	http    *http.Client
}

func New(baseURL, apiKey string) *Client {
	return &Client{
		baseURL: baseURL,
		apiKey:  apiKey,
		http:    &http.Client{},
	}
}

// CheckUser verifies that the given email exists in persona and is active.
func (c *Client) CheckUser(ctx context.Context, email string) error {
	endpoint := fmt.Sprintf("%s/internal/users/by-email?identifier=%s", c.baseURL, url.QueryEscape(email))

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-API-Key", c.apiKey)

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		return nil
	case http.StatusNotFound:
		return ErrUserNotFound
	case http.StatusForbidden:
		return ErrUserInactive
	default:
		return fmt.Errorf("persona returned unexpected status %d", resp.StatusCode)
	}
}

// GetUserContext fetches the full RBAC context for a user by ID.
// Called during JWT issuance to embed roles, permissions, and authz_version into the token.
func (c *Client) GetUserContext(ctx context.Context, userID string) (*UserContext, error) {
	endpoint := fmt.Sprintf("%s/internal/users/%s/context", c.baseURL, url.PathEscape(userID))

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-API-Key", c.apiKey)

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		var uc UserContext
		if err := json.NewDecoder(resp.Body).Decode(&uc); err != nil {
			return nil, fmt.Errorf("decode user context: %w", err)
		}
		return &uc, nil
	case http.StatusNotFound:
		return nil, ErrUserNotFound
	case http.StatusForbidden:
		return nil, ErrUserInactive
	default:
		return nil, fmt.Errorf("persona returned unexpected status %d", resp.StatusCode)
	}
}
