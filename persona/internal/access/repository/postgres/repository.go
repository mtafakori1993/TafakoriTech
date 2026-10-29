package postgres

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/mtafakori/persona/internal/permission/domain"
	user_domain "github.com/mtafakori/persona/internal/user/domain"
	"github.com/mtafakori/persona/shared/apperror"
)

// UserContext holds the full authorization snapshot for a user.
type UserContext struct {
	UserID       string
	Status       user_domain.Status
	Roles        []string
	Permissions  []string
	AuthzVersion int64
}

type Repository struct {
	db *pgxpool.Pool
}

// New returns a new postgres-backed access Repository.
func New(db *pgxpool.Pool) *Repository {
	return &Repository{db: db}
}

// GetUserPermissions returns all permissions assigned to a user through their roles.
func (r *Repository) GetUserPermissions(ctx context.Context, userID string) ([]domain.Permission, error) {
	const q = `
		SELECT DISTINCT p.id, p.name, p.slug, p.resource, p.action, p.description, p.created_at, p.updated_at
		FROM permissions p
		INNER JOIN role_permissions rp ON rp.permission_id = p.id AND rp.permission_id IS NOT NULL
		INNER JOIN user_roles ur ON ur.role_id = rp.role_id
		WHERE ur.user_id = $1
		  AND p.deleted_at IS NULL
		ORDER BY p.resource, p.action`

	rows, err := r.db.Query(ctx, q, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var perms []domain.Permission
	for rows.Next() {
		var p domain.Permission
		if err := rows.Scan(&p.ID, &p.Name, &p.Slug, &p.Resource, &p.Action, &p.Description, &p.CreatedAt, &p.UpdatedAt); err != nil {
			return nil, err
		}
		perms = append(perms, p)
	}
	if perms == nil {
		perms = []domain.Permission{}
	}
	return perms, rows.Err()
}

// GetUserContext returns a complete authorization context for a user, including
// their status, authz_version, all assigned role slugs, and all effective
// permission slugs aggregated across all roles.
func (r *Repository) GetUserContext(ctx context.Context, userID string) (*UserContext, error) {
	const q = `
		SELECT
			u.id,
			u.status,
			u.authz_version,
			COALESCE(array_agg(DISTINCT r.slug) FILTER (WHERE r.id IS NOT NULL), '{}') AS roles,
			COALESCE(array_agg(DISTINCT p.slug) FILTER (WHERE p.id IS NOT NULL), '{}') AS permissions
		FROM users u
		LEFT JOIN user_roles ur ON ur.user_id = u.id
		LEFT JOIN roles r       ON r.id = ur.role_id AND r.deleted_at IS NULL
		LEFT JOIN role_permissions rp ON rp.role_id = r.id
		LEFT JOIN permissions p ON p.id = rp.permission_id AND p.deleted_at IS NULL
		WHERE u.id = $1 AND u.deleted_at IS NULL
		GROUP BY u.id, u.status, u.authz_version`

	var uc UserContext
	err := r.db.QueryRow(ctx, q, userID).Scan(
		&uc.UserID,
		&uc.Status,
		&uc.AuthzVersion,
		&uc.Roles,
		&uc.Permissions,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperror.ErrNotFound
		}
		return nil, err
	}
	return &uc, nil
}
