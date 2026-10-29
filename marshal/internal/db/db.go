package db

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/mtafakori/marshal/internal/model"
)

type Store struct {
	pool *pgxpool.Pool
}

func New(ctx context.Context, connURL string) (*Store, error) {
	cfg, err := pgxpool.ParseConfig(connURL)
	if err != nil {
		return nil, fmt.Errorf("parse db config: %w", err)
	}
	cfg.MaxConns = 20
	cfg.MinConns = 2
	cfg.MaxConnLifetime = time.Hour
	cfg.MaxConnIdleTime = 30 * time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("create pool: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		return nil, fmt.Errorf("ping db: %w", err)
	}
	return &Store{pool: pool}, nil
}

func (s *Store) Close() { s.pool.Close() }

// Migrate creates the schema if it does not already exist. Safe to call on
// every startup — all statements use IF NOT EXISTS.
func (s *Store) Migrate(ctx context.Context) error {
	_, err := s.pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS projects (
			id          BIGSERIAL    PRIMARY KEY,
			name        TEXT         NOT NULL,
			description TEXT         NOT NULL DEFAULT '',
			color       TEXT         NOT NULL DEFAULT '#6750A4',
			created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
			updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
		);

		CREATE INDEX IF NOT EXISTS idx_projects_created_at ON projects (created_at DESC);

		CREATE OR REPLACE FUNCTION fn_set_updated_at()
		RETURNS TRIGGER LANGUAGE plpgsql AS $$
		BEGIN
			NEW.updated_at = NOW();
			RETURN NEW;
		END;
		$$;
	`)
	if err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	// Trigger cannot use IF NOT EXISTS, so recreate idempotently.
	_, err = s.pool.Exec(ctx, `
		DROP TRIGGER IF EXISTS trg_projects_updated_at ON projects;
		CREATE TRIGGER trg_projects_updated_at
			BEFORE UPDATE ON projects
			FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
	`)
	return err
}

const projectCols = `id, name, description, color, created_at, updated_at`

func scanProject(row pgx.Row) (*model.Project, error) {
	var p model.Project
	err := row.Scan(&p.ID, &p.Name, &p.Description, &p.Color, &p.CreatedAt, &p.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return &p, nil
}

// ListProjects returns all projects ordered newest first.
func (s *Store) ListProjects(ctx context.Context) ([]model.Project, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT `+projectCols+` FROM projects ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var projects []model.Project
	for rows.Next() {
		var p model.Project
		if err := rows.Scan(&p.ID, &p.Name, &p.Description, &p.Color,
			&p.CreatedAt, &p.UpdatedAt); err != nil {
			return nil, err
		}
		projects = append(projects, p)
	}
	return projects, rows.Err()
}

// GetProject returns a single project, or nil if not found.
func (s *Store) GetProject(ctx context.Context, id int64) (*model.Project, error) {
	p, err := scanProject(s.pool.QueryRow(ctx,
		`SELECT `+projectCols+` FROM projects WHERE id = $1`, id))
	if err == pgx.ErrNoRows {
		return nil, nil
	}
	return p, err
}

// CreateProject inserts a new project and returns it.
func (s *Store) CreateProject(ctx context.Context, name, description, color string) (*model.Project, error) {
	if color == "" {
		color = "#6750A4"
	}
	return scanProject(s.pool.QueryRow(ctx,
		`INSERT INTO projects (name, description, color)
		 VALUES ($1, $2, $3)
		 RETURNING `+projectCols,
		name, description, color))
}

// UpdateProject modifies an existing project and returns it, or nil if not found.
func (s *Store) UpdateProject(ctx context.Context, id int64, name, description, color string) (*model.Project, error) {
	p, err := scanProject(s.pool.QueryRow(ctx,
		`UPDATE projects SET name=$1, description=$2, color=$3
		 WHERE id=$4
		 RETURNING `+projectCols,
		name, description, color, id))
	if err == pgx.ErrNoRows {
		return nil, nil
	}
	return p, err
}

// DeleteProject removes a project. Returns true if a row was deleted.
func (s *Store) DeleteProject(ctx context.Context, id int64) (bool, error) {
	tag, err := s.pool.Exec(ctx, `DELETE FROM projects WHERE id = $1`, id)
	return tag.RowsAffected() > 0, err
}
