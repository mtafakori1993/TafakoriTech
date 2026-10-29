package postgres

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/mtafakori/persona/internal/permission/domain"
	perm_repo "github.com/mtafakori/persona/internal/permission/repository"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

type repository struct {
	db *pgxpool.Pool
}

// New returns a new postgres-backed permission Repository.
func New(db *pgxpool.Pool) perm_repo.Repository {
	return &repository{db: db}
}

func scanPermission(row pgx.Row) (*domain.Permission, error) {
	var p domain.Permission
	err := row.Scan(&p.ID, &p.Name, &p.Slug, &p.Resource, &p.Action, &p.Description, &p.CreatedAt, &p.UpdatedAt, &p.DeletedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperror.ErrNotFound
		}
		return nil, err
	}
	return &p, nil
}

func (r *repository) Create(ctx context.Context, p *domain.Permission) (*domain.Permission, error) {
	const q = `
		INSERT INTO permissions (name, slug, resource, action, description)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING id, name, slug, resource, action, description, created_at, updated_at, deleted_at`

	created, err := scanPermission(r.db.QueryRow(ctx, q, p.Name, p.Slug, p.Resource, p.Action, p.Description))
	if err != nil {
		if strings.Contains(err.Error(), "unique") {
			return nil, fmt.Errorf("%w: slug already exists", apperror.ErrConflict)
		}
		return nil, err
	}
	return created, nil
}

func (r *repository) List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.Permission], error) {
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
			fmt.Sprintf("(name ILIKE $%d OR slug ILIKE $%d OR resource ILIKE $%d)", argIdx, argIdx, argIdx))
		args = append(args, search)
		argIdx++
	}

	where := strings.Join(whereClauses, " AND ")

	var total int64
	if err := r.db.QueryRow(ctx, fmt.Sprintf(`SELECT COUNT(*) FROM permissions WHERE %s`, where), args...).Scan(&total); err != nil {
		return nil, err
	}

	fetchLimit := page.Limit + 1
	dataArgs := append(args, fetchLimit)
	dataQ := fmt.Sprintf(`
		SELECT id, name, slug, resource, action, description, created_at, updated_at, deleted_at
		FROM permissions WHERE %s
		ORDER BY created_at DESC, id DESC
		LIMIT $%d`, where, argIdx)

	rows, err := r.db.Query(ctx, dataQ, dataArgs...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var perms []domain.Permission
	for rows.Next() {
		var p domain.Permission
		if err := rows.Scan(&p.ID, &p.Name, &p.Slug, &p.Resource, &p.Action, &p.Description, &p.CreatedAt, &p.UpdatedAt, &p.DeletedAt); err != nil {
			return nil, err
		}
		perms = append(perms, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	result := &pagination.Result[domain.Permission]{Total: total}
	if len(perms) > page.Limit {
		result.NextCursor = perms[page.Limit-1].ID
		result.Items = perms[:page.Limit]
	} else {
		result.Items = perms
	}
	if result.Items == nil {
		result.Items = []domain.Permission{}
	}
	return result, nil
}

func (r *repository) GetByID(ctx context.Context, id string) (*domain.Permission, error) {
	const q = `
		SELECT id, name, slug, resource, action, description, created_at, updated_at, deleted_at
		FROM permissions WHERE id = $1 AND deleted_at IS NULL`
	return scanPermission(r.db.QueryRow(ctx, q, id))
}

func (r *repository) Update(ctx context.Context, id string, fields perm_repo.UpdateFields) (*domain.Permission, error) {
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
		UPDATE permissions SET %s
		WHERE id = $%d AND deleted_at IS NULL
		RETURNING id, name, slug, resource, action, description, created_at, updated_at, deleted_at`,
		strings.Join(setClauses, ", "), argIdx)

	return scanPermission(r.db.QueryRow(ctx, q, args...))
}

func (r *repository) Delete(ctx context.Context, id string) error {
	const q = `UPDATE permissions SET deleted_at = NOW() WHERE id = $1 AND deleted_at IS NULL`
	tag, err := r.db.Exec(ctx, q, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return apperror.ErrNotFound
	}
	return nil
}
