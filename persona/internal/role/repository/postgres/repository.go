package postgres

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	permission_domain "github.com/mtafakori/persona/internal/permission/domain"
	"github.com/mtafakori/persona/internal/role/domain"
	role_repo "github.com/mtafakori/persona/internal/role/repository"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

type repository struct {
	db *pgxpool.Pool
}

// New returns a new postgres-backed role Repository.
func New(db *pgxpool.Pool) role_repo.Repository {
	return &repository{db: db}
}

func scanRole(row pgx.Row) (*domain.Role, error) {
	var r domain.Role
	err := row.Scan(&r.ID, &r.Name, &r.Slug, &r.Description, &r.CreatedAt, &r.UpdatedAt, &r.DeletedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperror.ErrNotFound
		}
		return nil, err
	}
	return &r, nil
}

func (r *repository) Create(ctx context.Context, role *domain.Role) (*domain.Role, error) {
	const q = `
		INSERT INTO roles (name, slug, description)
		VALUES ($1, $2, $3)
		RETURNING id, name, slug, description, created_at, updated_at, deleted_at`

	created, err := scanRole(r.db.QueryRow(ctx, q, role.Name, role.Slug, role.Description))
	if err != nil {
		if strings.Contains(err.Error(), "unique") {
			return nil, fmt.Errorf("%w: slug already exists", apperror.ErrConflict)
		}
		return nil, err
	}
	return created, nil
}

func (r *repository) List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.Role], error) {
	args := []interface{}{}
	argIdx := 1
	whereClauses := []string{"deleted_at IS NULL"}

	if page.Cursor != "" {
		whereClauses = append(whereClauses, fmt.Sprintf("id < $%d", argIdx))
		args = append(args, page.Cursor)
		argIdx++
	}
	if page.Search != "" {
		search := "%" + page.Search + "%"
		whereClauses = append(whereClauses,
			fmt.Sprintf("(name ILIKE $%d OR slug ILIKE $%d)", argIdx, argIdx))
		args = append(args, search)
		argIdx++
	}

	where := strings.Join(whereClauses, " AND ")

	var total int64
	if err := r.db.QueryRow(ctx, fmt.Sprintf(`SELECT COUNT(*) FROM roles WHERE %s`, where), args...).Scan(&total); err != nil {
		return nil, err
	}

	fetchLimit := page.Limit + 1
	dataArgs := append(args, fetchLimit)
	dataQ := fmt.Sprintf(`
		SELECT id, name, slug, description, created_at, updated_at, deleted_at
		FROM roles WHERE %s
		ORDER BY created_at DESC, id DESC
		LIMIT $%d`, where, argIdx)

	rows, err := r.db.Query(ctx, dataQ, dataArgs...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var roles []domain.Role
	for rows.Next() {
		var ro domain.Role
		if err := rows.Scan(&ro.ID, &ro.Name, &ro.Slug, &ro.Description, &ro.CreatedAt, &ro.UpdatedAt, &ro.DeletedAt); err != nil {
			return nil, err
		}
		roles = append(roles, ro)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	result := &pagination.Result[domain.Role]{Total: total}
	if len(roles) > page.Limit {
		result.NextCursor = roles[page.Limit-1].ID
		result.Items = roles[:page.Limit]
	} else {
		result.Items = roles
	}
	if result.Items == nil {
		result.Items = []domain.Role{}
	}
	return result, nil
}

func (r *repository) GetByID(ctx context.Context, id string) (*domain.Role, error) {
	const q = `
		SELECT id, name, slug, description, created_at, updated_at, deleted_at
		FROM roles WHERE id = $1 AND deleted_at IS NULL`
	return scanRole(r.db.QueryRow(ctx, q, id))
}

func (r *repository) Update(ctx context.Context, id string, fields role_repo.UpdateFields) (*domain.Role, error) {
	setClauses := []string{}
	args := []interface{}{}
	argIdx := 1

	if fields.Name != nil {
		setClauses = append(setClauses, fmt.Sprintf("name = $%d", argIdx))
		args = append(args, *fields.Name)
		argIdx++
	}
	if fields.Description != nil {
		setClauses = append(setClauses, fmt.Sprintf("description = $%d", argIdx))
		args = append(args, *fields.Description)
		argIdx++
	}

	if len(setClauses) == 0 {
		return r.GetByID(ctx, id)
	}

	args = append(args, id)
	q := fmt.Sprintf(`
		UPDATE roles SET %s
		WHERE id = $%d AND deleted_at IS NULL
		RETURNING id, name, slug, description, created_at, updated_at, deleted_at`,
		strings.Join(setClauses, ", "), argIdx)

	return scanRole(r.db.QueryRow(ctx, q, args...))
}

func (r *repository) Delete(ctx context.Context, id string) error {
	const q = `UPDATE roles SET deleted_at = NOW() WHERE id = $1 AND deleted_at IS NULL`
	tag, err := r.db.Exec(ctx, q, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return apperror.ErrNotFound
	}
	return nil
}

func (r *repository) AssignPermission(ctx context.Context, roleID, permissionID string) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	const insert = `INSERT INTO role_permissions (role_id, permission_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`
	if _, err := tx.Exec(ctx, insert, roleID, permissionID); err != nil {
		return err
	}

	// Bump authz_version for every user who holds this role so their cached
	// tokens become stale and Sentinel re-fetches the context on next access.
	const bump = `
		UPDATE users SET authz_version = authz_version + 1
		WHERE id IN (SELECT user_id FROM user_roles WHERE role_id = $1)`
	if _, err := tx.Exec(ctx, bump, roleID); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (r *repository) RemovePermission(ctx context.Context, roleID, permissionID string) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	const del = `DELETE FROM role_permissions WHERE role_id = $1 AND permission_id = $2`
	if _, err := tx.Exec(ctx, del, roleID, permissionID); err != nil {
		return err
	}

	const bump = `
		UPDATE users SET authz_version = authz_version + 1
		WHERE id IN (SELECT user_id FROM user_roles WHERE role_id = $1)`
	if _, err := tx.Exec(ctx, bump, roleID); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (r *repository) ListPermissions(ctx context.Context, roleID string) ([]permission_domain.Permission, error) {
	const q = `
		SELECT p.id, p.name, p.slug, p.resource, p.action, p.description, p.created_at, p.updated_at, p.deleted_at
		FROM permissions p
		INNER JOIN role_permissions rp ON rp.permission_id = p.id
		WHERE rp.role_id = $1 AND p.deleted_at IS NULL
		ORDER BY p.resource, p.action`

	rows, err := r.db.Query(ctx, q, roleID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var perms []permission_domain.Permission
	for rows.Next() {
		var p permission_domain.Permission
		if err := rows.Scan(&p.ID, &p.Name, &p.Slug, &p.Resource, &p.Action, &p.Description, &p.CreatedAt, &p.UpdatedAt, &p.DeletedAt); err != nil {
			return nil, err
		}
		perms = append(perms, p)
	}
	if perms == nil {
		perms = []permission_domain.Permission{}
	}
	return perms, rows.Err()
}
