package postgres

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/mtafakori/persona/internal/team/domain"
	team_repo "github.com/mtafakori/persona/internal/team/repository"
	user_domain "github.com/mtafakori/persona/internal/user/domain"
	"github.com/mtafakori/persona/shared/apperror"
	"github.com/mtafakori/persona/shared/pagination"
)

type repository struct {
	db *pgxpool.Pool
}

// New returns a new postgres-backed team Repository.
func New(db *pgxpool.Pool) team_repo.Repository {
	return &repository{db: db}
}

func scanTeam(row pgx.Row) (*domain.Team, error) {
	var t domain.Team
	err := row.Scan(&t.ID, &t.Name, &t.Description, &t.CreatedAt, &t.UpdatedAt, &t.DeletedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, apperror.ErrNotFound
		}
		return nil, err
	}
	return &t, nil
}

func (r *repository) Create(ctx context.Context, t *domain.Team) (*domain.Team, error) {
	const q = `
		INSERT INTO teams (name, description)
		VALUES ($1, $2)
		RETURNING id, name, description, created_at, updated_at, deleted_at`
	return scanTeam(r.db.QueryRow(ctx, q, t.Name, t.Description))
}

func (r *repository) List(ctx context.Context, page pagination.Page) (*pagination.Result[domain.Team], error) {
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
		whereClauses = append(whereClauses, fmt.Sprintf("name ILIKE $%d", argIdx))
		args = append(args, search)
		argIdx++
	}

	where := strings.Join(whereClauses, " AND ")

	var total int64
	if err := r.db.QueryRow(ctx, fmt.Sprintf(`SELECT COUNT(*) FROM teams WHERE %s`, where), args...).Scan(&total); err != nil {
		return nil, err
	}

	fetchLimit := page.Limit + 1
	dataArgs := append(args, fetchLimit)
	dataQ := fmt.Sprintf(`
		SELECT id, name, description, created_at, updated_at, deleted_at
		FROM teams WHERE %s
		ORDER BY created_at DESC, id DESC
		LIMIT $%d`, where, argIdx)

	rows, err := r.db.Query(ctx, dataQ, dataArgs...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var teams []domain.Team
	for rows.Next() {
		var t domain.Team
		if err := rows.Scan(&t.ID, &t.Name, &t.Description, &t.CreatedAt, &t.UpdatedAt, &t.DeletedAt); err != nil {
			return nil, err
		}
		teams = append(teams, t)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	result := &pagination.Result[domain.Team]{Total: total}
	if len(teams) > page.Limit {
		result.NextCursor = teams[page.Limit-1].ID
		result.Items = teams[:page.Limit]
	} else {
		result.Items = teams
	}
	if result.Items == nil {
		result.Items = []domain.Team{}
	}
	return result, nil
}

func (r *repository) GetByID(ctx context.Context, id string) (*domain.Team, error) {
	const q = `
		SELECT id, name, description, created_at, updated_at, deleted_at
		FROM teams WHERE id = $1 AND deleted_at IS NULL`
	return scanTeam(r.db.QueryRow(ctx, q, id))
}

func (r *repository) Update(ctx context.Context, id string, fields team_repo.UpdateFields) (*domain.Team, error) {
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
		UPDATE teams SET %s
		WHERE id = $%d AND deleted_at IS NULL
		RETURNING id, name, description, created_at, updated_at, deleted_at`,
		strings.Join(setClauses, ", "), argIdx)

	return scanTeam(r.db.QueryRow(ctx, q, args...))
}

func (r *repository) Delete(ctx context.Context, id string) error {
	const q = `UPDATE teams SET deleted_at = NOW() WHERE id = $1 AND deleted_at IS NULL`
	tag, err := r.db.Exec(ctx, q, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return apperror.ErrNotFound
	}
	return nil
}

func (r *repository) AddUser(ctx context.Context, teamID, userID string) error {
	const q = `INSERT INTO team_users (team_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`
	_, err := r.db.Exec(ctx, q, teamID, userID)
	return err
}

func (r *repository) RemoveUser(ctx context.Context, teamID, userID string) error {
	const q = `DELETE FROM team_users WHERE team_id = $1 AND user_id = $2`
	_, err := r.db.Exec(ctx, q, teamID, userID)
	return err
}

func (r *repository) ListUsers(ctx context.Context, teamID string) ([]user_domain.User, error) {
	const q = `
		SELECT u.id, u.first_name, u.last_name, u.email, u.phone, u.avatar_url, u.status, u.created_at, u.updated_at, u.deleted_at
		FROM users u
		INNER JOIN team_users tu ON tu.user_id = u.id
		WHERE tu.team_id = $1 AND u.deleted_at IS NULL
		ORDER BY u.first_name, u.last_name`

	rows, err := r.db.Query(ctx, q, teamID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var users []user_domain.User
	for rows.Next() {
		var u user_domain.User
		if err := rows.Scan(
			&u.ID, &u.FirstName, &u.LastName, &u.Email,
			&u.Phone, &u.AvatarURL, &u.Status,
			&u.CreatedAt, &u.UpdatedAt, &u.DeletedAt,
		); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	if users == nil {
		users = []user_domain.User{}
	}
	return users, rows.Err()
}
