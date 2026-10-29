package postgres

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	role_domain "github.com/mtafakori/persona/internal/role/domain"
	team_domain "github.com/mtafakori/persona/internal/team/domain"
	"github.com/mtafakori/persona/internal/user/domain"
	user_repo "github.com/mtafakori/persona/internal/user/repository"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

type repository struct {
	db *pgxpool.Pool
}

// New returns a new postgres-backed user Repository.
func New(db *pgxpool.Pool) user_repo.Repository {
	return &repository{db: db}
}

func scanUser(row pgx.Row) (*domain.User, error) {
	var u domain.User
	err := row.Scan(
		&u.ID, &u.FirstName, &u.LastName, &u.Email,
		&u.Phone, &u.AvatarURL, &u.Status, &u.AuthzVersion,
		&u.CreatedAt, &u.UpdatedAt, &u.DeletedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperror.ErrNotFound
		}
		return nil, err
	}
	return &u, nil
}

func (r *repository) Create(ctx context.Context, u *domain.User) (*domain.User, error) {
	const q = `
		INSERT INTO users (first_name, last_name, email, phone, avatar_url, status)
		VALUES ($1, $2, $3, $4, $5, $6)
		RETURNING id, first_name, last_name, email, phone, avatar_url, status, authz_version, created_at, updated_at, deleted_at`

	row := r.db.QueryRow(ctx, q, u.FirstName, u.LastName, u.Email, u.Phone, u.AvatarURL, u.Status)
	created, err := scanUser(row)
	if err != nil {
		if strings.Contains(err.Error(), "unique") {
			return nil, fmt.Errorf("%w: email already exists", apperror.ErrConflict)
		}
		return nil, err
	}
	return created, nil
}

func (r *repository) List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.User], error) {
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
			fmt.Sprintf("(first_name ILIKE $%d OR last_name ILIKE $%d OR email ILIKE $%d)", argIdx, argIdx, argIdx))
		args = append(args, search)
		argIdx++
	}

	if page.Status != "" {
		whereClauses = append(whereClauses, fmt.Sprintf("status = $%d", argIdx))
		args = append(args, page.Status)
		argIdx++
	}

	where := strings.Join(whereClauses, " AND ")

	countQ := fmt.Sprintf(`SELECT COUNT(*) FROM users WHERE %s`, where)
	var total int64
	if err := r.db.QueryRow(ctx, countQ, args...).Scan(&total); err != nil {
		return nil, err
	}

	fetchLimit := page.Limit + 1
	dataArgs := append(args, fetchLimit)
	dataQ := fmt.Sprintf(`
		SELECT id, first_name, last_name, email, phone, avatar_url, status, authz_version, created_at, updated_at, deleted_at
		FROM users
		WHERE %s
		ORDER BY created_at DESC, id DESC
		LIMIT $%d`, where, argIdx)

	rows, err := r.db.Query(ctx, dataQ, dataArgs...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var users []domain.User
	for rows.Next() {
		var u domain.User
		if err := rows.Scan(
			&u.ID, &u.FirstName, &u.LastName, &u.Email,
			&u.Phone, &u.AvatarURL, &u.Status, &u.AuthzVersion,
			&u.CreatedAt, &u.UpdatedAt, &u.DeletedAt,
		); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	result := &pagination.Result[domain.User]{Total: total}
	if len(users) > page.Limit {
		result.NextCursor = users[page.Limit-1].ID
		result.Items = users[:page.Limit]
	} else {
		result.Items = users
	}
	if result.Items == nil {
		result.Items = []domain.User{}
	}
	return result, nil
}

func (r *repository) GetByID(ctx context.Context, id string) (*domain.User, error) {
	const q = `
		SELECT id, first_name, last_name, email, phone, avatar_url, status, authz_version, created_at, updated_at, deleted_at
		FROM users WHERE id = $1 AND deleted_at IS NULL`
	return scanUser(r.db.QueryRow(ctx, q, id))
}

func (r *repository) GetByEmail(ctx context.Context, email string) (*domain.User, error) {
	const q = `
		SELECT id, first_name, last_name, email, phone, avatar_url, status, authz_version, created_at, updated_at, deleted_at
		FROM users WHERE email = $1 AND deleted_at IS NULL`
	return scanUser(r.db.QueryRow(ctx, q, email))
}

func (r *repository) GetByIdentifier(ctx context.Context, identifier string) (*domain.User, error) {
	const q = `
		SELECT id, first_name, last_name, email, phone, avatar_url, status, authz_version, created_at, updated_at, deleted_at
		FROM users WHERE (email = $1 OR phone = $1) AND deleted_at IS NULL
		LIMIT 1`
	return scanUser(r.db.QueryRow(ctx, q, identifier))
}

func (r *repository) Update(ctx context.Context, id string, fields user_repo.UpdateFields) (*domain.User, error) {
	setClauses := []string{}
	args := []interface{}{}
	argIdx := 1

	if fields.FirstName != nil {
		setClauses = append(setClauses, fmt.Sprintf("first_name = $%d", argIdx))
		args = append(args, *fields.FirstName)
		argIdx++
	}
	if fields.LastName != nil {
		setClauses = append(setClauses, fmt.Sprintf("last_name = $%d", argIdx))
		args = append(args, *fields.LastName)
		argIdx++
	}
	if fields.Phone != nil {
		setClauses = append(setClauses, fmt.Sprintf("phone = $%d", argIdx))
		args = append(args, *fields.Phone)
		argIdx++
	}
	if fields.AvatarURL != nil {
		setClauses = append(setClauses, fmt.Sprintf("avatar_url = $%d", argIdx))
		args = append(args, *fields.AvatarURL)
		argIdx++
	}
	if fields.Status != nil {
		setClauses = append(setClauses, fmt.Sprintf("status = $%d", argIdx))
		args = append(args, string(*fields.Status))
		argIdx++
	}

	if len(setClauses) == 0 {
		return r.GetByID(ctx, id)
	}

	args = append(args, id)
	q := fmt.Sprintf(`
		UPDATE users SET %s
		WHERE id = $%d AND deleted_at IS NULL
		RETURNING id, first_name, last_name, email, phone, avatar_url, status, authz_version, created_at, updated_at, deleted_at`,
		strings.Join(setClauses, ", "), argIdx)

	return scanUser(r.db.QueryRow(ctx, q, args...))
}

func (r *repository) Delete(ctx context.Context, id string) error {
	const q = `UPDATE users SET deleted_at = NOW() WHERE id = $1 AND deleted_at IS NULL`
	tag, err := r.db.Exec(ctx, q, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return apperror.ErrNotFound
	}
	return nil
}

func (r *repository) AssignRole(ctx context.Context, userID, roleID string) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	const insert = `INSERT INTO user_roles (user_id, role_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`
	if _, err := tx.Exec(ctx, insert, userID, roleID); err != nil {
		return err
	}

	const bump = `UPDATE users SET authz_version = authz_version + 1 WHERE id = $1`
	if _, err := tx.Exec(ctx, bump, userID); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (r *repository) RemoveRole(ctx context.Context, userID, roleID string) error {
	tx, err := r.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	const del = `DELETE FROM user_roles WHERE user_id = $1 AND role_id = $2`
	if _, err := tx.Exec(ctx, del, userID, roleID); err != nil {
		return err
	}

	const bump = `UPDATE users SET authz_version = authz_version + 1 WHERE id = $1`
	if _, err := tx.Exec(ctx, bump, userID); err != nil {
		return err
	}

	return tx.Commit(ctx)
}

func (r *repository) ListRoles(ctx context.Context, userID string) ([]role_domain.Role, error) {
	const q = `
		SELECT r.id, r.name, r.slug, r.description, r.created_at, r.updated_at, r.deleted_at
		FROM roles r
		INNER JOIN user_roles ur ON ur.role_id = r.id
		WHERE ur.user_id = $1 AND r.deleted_at IS NULL
		ORDER BY r.name`

	rows, err := r.db.Query(ctx, q, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var roles []role_domain.Role
	for rows.Next() {
		var ro role_domain.Role
		if err := rows.Scan(&ro.ID, &ro.Name, &ro.Slug, &ro.Description, &ro.CreatedAt, &ro.UpdatedAt, &ro.DeletedAt); err != nil {
			return nil, err
		}
		roles = append(roles, ro)
	}
	if roles == nil {
		roles = []role_domain.Role{}
	}
	return roles, rows.Err()
}

func (r *repository) ListTeams(ctx context.Context, userID string) ([]team_domain.Team, error) {
	const q = `
		SELECT t.id, t.name, t.description, t.created_at, t.updated_at, t.deleted_at
		FROM teams t
		INNER JOIN team_users tu ON tu.team_id = t.id
		WHERE tu.user_id = $1 AND t.deleted_at IS NULL
		ORDER BY t.name`

	rows, err := r.db.Query(ctx, q, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var teams []team_domain.Team
	for rows.Next() {
		var t team_domain.Team
		if err := rows.Scan(&t.ID, &t.Name, &t.Description, &t.CreatedAt, &t.UpdatedAt, &t.DeletedAt); err != nil {
			return nil, err
		}
		teams = append(teams, t)
	}
	if teams == nil {
		teams = []team_domain.Team{}
	}
	return teams, rows.Err()
}
