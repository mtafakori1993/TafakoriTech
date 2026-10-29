# Herald CMS

Headless Content Management microservice built with Go (Gin + GORM + PostgreSQL).  
Manages **Static Pages**, **Blog Posts**, and **Articles** behind a single REST API.

---

## Table of Contents

- [Architecture](#architecture)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Running Migrations](#running-migrations)
- [API Reference](#api-reference)
  - [Health](#health)
  - [Pages](#pages)
  - [Posts](#posts)
  - [Articles](#articles)
  - [Publishing](#publishing)
  - [Search](#search)
- [Authentication](#authentication)
- [Pagination](#pagination)
- [Example Requests](#example-requests)
- [Development](#development)

---

## Architecture

```
herald/
├── cmd/herald/           Entry point
├── internal/
│   ├── content/
│   │   ├── domain/       Domain models (Content, Page, Post, Article, Tag, Category)
│   │   ├── repository/   Repository interfaces + GORM implementations
│   │   ├── service/      Business logic layer
│   │   └── transport/
│   │       └── rest/     Gin handlers, DTOs, middleware, router
│   └── shared/
│       ├── apperror/     Typed application errors
│       ├── config/       Environment-based config loader
│       ├── logger/       Uber Zap structured logger
│       └── pagination/   Generic pagination helpers
├── migrations/           Raw SQL migration files (run in order)
└── docs/                 OpenAPI / Swagger spec
```

**Dependency flow:** `handler → service → repository → domain`  
No layer imports anything above it. Services depend on repository *interfaces*, not GORM directly.

---

## Getting Started

### Prerequisites

- Go 1.22+
- PostgreSQL 16 (separate `herald` database)
- Docker (optional)

### Local Setup

```bash
# 1. Install dependencies
go mod tidy

# 2. Copy and fill environment
cp ../.env.example .env

# 3. Apply migrations
make migrate DB_URL="postgres://user:pass@127.0.0.1:5445/herald?sslmode=disable"

# 4. Seed initial data (system pages, categories, tags)
make seed DB_URL="postgres://user:pass@127.0.0.1:5445/herald?sslmode=disable"

# 5. Run
make run
```

### Docker (via infra stack)

Herald is part of the `TafakoriTech` Docker Swarm stack.  
The service is available through Kong at `/herald/*`.

```bash
cd ../infra
docker stack deploy -c docker-stack.yml TafakoriTech
```

---

## Environment Variables

| Variable       | Required | Default       | Description                                      |
|----------------|----------|---------------|--------------------------------------------------|
| `DATABASE_URL` | Yes      | —             | PostgreSQL DSN for the `herald` database         |
| `API_KEY`      | Yes      | —             | Secret key for write endpoints (`X-API-Key`)     |
| `PORT`         | No       | `8092`        | HTTP listen port                                 |
| `HOST`         | No       | `0.0.0.0`    | HTTP bind address                                |
| `ENV`          | No       | `development` | Set to `production` for JSON logs, error-only DB |

**Example DSN:**
```
DATABASE_URL=postgresql://commander:password@postgres:5432/herald?sslmode=disable
```

---

## Running Migrations

SQL files in `migrations/` must be applied in order against the `herald` database:

```bash
# Apply all migrations
make migrate DB_URL="postgresql://user:pass@host:5445/herald?sslmode=disable"

# Or manually with psql
psql "$DB_URL" -f migrations/001_create_contents.sql
psql "$DB_URL" -f migrations/002_create_categories.sql
psql "$DB_URL" -f migrations/003_create_tags.sql
psql "$DB_URL" -f migrations/004_create_pages.sql
psql "$DB_URL" -f migrations/005_create_posts.sql
psql "$DB_URL" -f migrations/006_create_articles.sql
psql "$DB_URL" -f migrations/007_seed.sql   # optional seed data
```

> The service also runs GORM `AutoMigrate` on startup to keep the schema in sync during development. In production, prefer the SQL files for full control.

---

## API Reference

**Base URL (direct):** `http://host:8092/api/v1`  
**Base URL (via Kong):** `http://host:8080/herald/api/v1`

### Content Statuses

| Status      | Description                         |
|-------------|-------------------------------------|
| `draft`     | Not publicly visible (default)      |
| `published` | Visible on all public GET endpoints |

Public `GET` endpoints only return **published** content.  
Write endpoints (`POST`, `PUT`, `DELETE`) work regardless of status.

---

### Health

#### `GET /health`

Returns service status. No authentication required.

**Response `200`**
```json
{
  "status": "ok",
  "service": "herald"
}
```

---

### Pages

Static pages such as About Us, Contact, Privacy Policy, and Terms of Service.

---

#### `GET /api/v1/pages/:slug`

Fetch a single published page by its slug.

**Path Parameters**

| Parameter | Type   | Description |
|-----------|--------|-------------|
| `slug`    | string | Page slug   |

**Response `200`**
```json
{
  "id": 1,
  "content_id": 3,
  "route": "/about",
  "template": "default",
  "is_system": true,
  "content": {
    "id": 3,
    "type": "page",
    "title": "About Us",
    "slug": "about-us",
    "excerpt": "Learn about our mission.",
    "status": "published",
    "seo_title": "About Us | Herald",
    "seo_description": "Learn about our company and mission.",
    "author_id": 1,
    "published_at": "2026-06-11T10:00:00Z",
    "created_at": "2026-06-11T09:00:00Z",
    "updated_at": "2026-06-11T10:00:00Z"
  }
}
```

**Response `404`**
```json
{ "error": "page \"about-us\" not found" }
```

---

#### `POST /api/v1/pages` 🔒

Create a new page.

**Request Body**

| Field             | Type    | Required | Description                          |
|-------------------|---------|----------|--------------------------------------|
| `title`           | string  | Yes      | Page title (max 500 chars)           |
| `route`           | string  | Yes      | URL route, e.g. `/about`             |
| `slug`            | string  | No       | Auto-generated from title if omitted |
| `body`            | string  | No       | HTML body content                    |
| `excerpt`         | string  | No       | Short summary                        |
| `template`        | string  | No       | Template name (default: `default`)   |
| `is_system`       | boolean | No       | Marks as a protected system page     |
| `seo_title`       | string  | No       | SEO `<title>` override               |
| `seo_description` | string  | No       | SEO meta description                 |

**Response `201`** — returns the created page object (same shape as GET).

**Response `400`** — validation error.

---

#### `PUT /api/v1/pages/:id` 🔒

Update an existing page. `:id` is the **content ID**.

**Path Parameters**

| Parameter | Type    | Description |
|-----------|---------|-------------|
| `id`      | integer | Content ID  |

**Request Body** — same fields as `POST /pages`, all fields required.

**Response `200`** — updated page object.

**Response `404`** — page not found.

---

#### `DELETE /api/v1/pages/:id` 🔒

Delete a page and its associated content record. `:id` is the **content ID**.

**Response `204`** — deleted successfully.

**Response `404`** — page not found.

---

### Posts

Blog post entries, optionally grouped by category and tagged.

---

#### `GET /api/v1/posts`

List all published posts, newest first. Supports [pagination](#pagination).

**Query Parameters**

| Parameter | Type    | Default | Description     |
|-----------|---------|---------|-----------------|
| `page`    | integer | `1`     | Page number     |
| `limit`   | integer | `20`    | Items per page (max 100) |

**Response `200`**
```json
{
  "data": [
    {
      "id": 1,
      "content_id": 10,
      "category_id": 2,
      "category": {
        "id": 2,
        "name": "Engineering",
        "slug": "engineering",
        "description": "Technical posts about software and systems"
      },
      "tags": [
        { "id": 1, "name": "Go",  "slug": "go" },
        { "id": 4, "name": "API", "slug": "api" }
      ],
      "content": {
        "id": 10,
        "type": "post",
        "title": "My First Post",
        "slug": "my-first-post",
        "excerpt": "Short summary",
        "status": "published",
        "seo_title": "",
        "seo_description": "",
        "author_id": 1,
        "published_at": "2026-06-11T12:00:00Z",
        "created_at": "2026-06-11T11:00:00Z",
        "updated_at": "2026-06-11T12:00:00Z"
      }
    }
  ],
  "total": 42,
  "page": 1,
  "limit": 20,
  "total_pages": 3
}
```

---

#### `GET /api/v1/posts/:slug`

Fetch a single published post by slug.

**Response `200`** — single post object (same shape as list item).

**Response `404`**
```json
{ "error": "post \"my-first-post\" not found" }
```

---

#### `POST /api/v1/posts` 🔒

Create a new blog post.

**Request Body**

| Field             | Type      | Required | Description                          |
|-------------------|-----------|----------|--------------------------------------|
| `title`           | string    | Yes      | Post title (max 500 chars)           |
| `slug`            | string    | No       | Auto-generated from title if omitted |
| `body`            | string    | No       | HTML body content                    |
| `excerpt`         | string    | No       | Short summary                        |
| `category_id`     | integer   | No       | ID of an existing category           |
| `tags`            | string[]  | No       | Tag names — created automatically if new |
| `seo_title`       | string    | No       | SEO `<title>` override               |
| `seo_description` | string    | No       | SEO meta description                 |

**Response `201`** — created post object.

---

### Articles

Long-form editorial content. Read time is estimated automatically at ~200 words per minute.

---

#### `GET /api/v1/articles`

List all published articles, newest first. Supports [pagination](#pagination).

**Query Parameters** — same as `GET /posts`.

**Response `200`**
```json
{
  "data": [
    {
      "id": 1,
      "content_id": 20,
      "read_time": 8,
      "tags": [
        { "id": 1, "name": "Go", "slug": "go" }
      ],
      "content": {
        "id": 20,
        "type": "article",
        "title": "Deep Dive into Go",
        "slug": "deep-dive-into-go",
        "excerpt": "A comprehensive Go guide",
        "status": "published",
        "seo_title": "Deep Dive into Go | Articles",
        "seo_description": "A comprehensive Go guide",
        "author_id": 1,
        "published_at": "2026-06-11T14:00:00Z",
        "created_at": "2026-06-11T13:00:00Z",
        "updated_at": "2026-06-11T14:00:00Z"
      }
    }
  ],
  "total": 7,
  "page": 1,
  "limit": 20,
  "total_pages": 1
}
```

---

#### `GET /api/v1/articles/:slug`

Fetch a single published article by slug.

**Response `200`** — single article object.

**Response `404`**
```json
{ "error": "article \"deep-dive-into-go\" not found" }
```

---

#### `POST /api/v1/articles` 🔒

Create a new article. Read time is calculated automatically from the body word count.

**Request Body**

| Field             | Type     | Required | Description                          |
|-------------------|----------|----------|--------------------------------------|
| `title`           | string   | Yes      | Article title (max 500 chars)        |
| `slug`            | string   | No       | Auto-generated from title if omitted |
| `body`            | string   | No       | HTML body content                    |
| `excerpt`         | string   | No       | Short summary                        |
| `tags`            | string[] | No       | Tag names — created automatically if new |
| `seo_title`       | string   | No       | SEO `<title>` override               |
| `seo_description` | string   | No       | SEO meta description                 |

**Response `201`** — created article object including calculated `read_time`.

---

### Publishing

Content is created as `draft` and must be explicitly published.  
Both endpoints accept any content type (page, post, article).

---

#### `POST /api/v1/publish/:id` 🔒

Publish a content item. Sets `status = published` and stamps `published_at`.

**Path Parameters**

| Parameter | Type    | Description |
|-----------|---------|-------------|
| `id`      | integer | Content ID  |

**Response `204`** — published.

**Response `404`**
```json
{ "error": "content 99 not found" }
```

---

#### `POST /api/v1/unpublish/:id` 🔒

Revert a content item to draft. Clears `published_at`.

**Path Parameters**

| Parameter | Type    | Description |
|-----------|---------|-------------|
| `id`      | integer | Content ID  |

**Response `204`** — unpublished.

**Response `404`**
```json
{ "error": "content 99 not found" }
```

---

### Search

Full-text search across all **published** content (pages, posts, articles).  
Searches `title`, `body`, and `excerpt` fields using PostgreSQL `ILIKE`.

---

#### `GET /api/v1/search`

**Query Parameters**

| Parameter | Type    | Required | Default | Description              |
|-----------|---------|----------|---------|--------------------------|
| `q`       | string  | Yes      | —       | Search query             |
| `page`    | integer | No       | `1`     | Page number              |
| `limit`   | integer | No       | `20`    | Items per page (max 100) |

**Response `200`**
```json
{
  "data": [
    {
      "id": 10,
      "type": "post",
      "title": "My First Post",
      "slug": "my-first-post",
      "excerpt": "Short summary mentioning Go",
      "status": "published",
      "seo_title": "",
      "seo_description": "",
      "author_id": 1,
      "published_at": "2026-06-11T12:00:00Z",
      "created_at": "2026-06-11T11:00:00Z",
      "updated_at": "2026-06-11T12:00:00Z"
    }
  ],
  "total": 3,
  "page": 1,
  "limit": 20,
  "total_pages": 1
}
```

**Response `400`** — missing `q` parameter.
```json
{ "error": "query parameter 'q' is required" }
```

---

## Authentication

Write endpoints are protected by an API key passed in the request header:

```
X-API-Key: your-secret-key
```

Alternatively as a query parameter (less preferred):

```
GET /api/v1/posts?api_key=your-secret-key
```

The key is configured via the `API_KEY` environment variable.  
Requests without a valid key receive `401 Unauthorized`.

---

## Pagination

All list endpoints return a consistent pagination envelope:

```json
{
  "data":        [],
  "total":       100,
  "page":        1,
  "limit":       20,
  "total_pages": 5
}
```

| Parameter | Default | Maximum |
|-----------|---------|---------|
| `page`    | 1       | —       |
| `limit`   | 20      | 100     |

---

## Example Requests

### Create and publish an About Us page

```bash
# 1. Create
curl -s -X POST http://127.0.0.1:8092/api/v1/pages \
  -H "X-API-Key: your-secret-key" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "About Us",
    "route": "/about",
    "body": "<h1>About Us</h1><p>We build great software.</p>",
    "excerpt": "Learn about our mission.",
    "is_system": true,
    "seo_title": "About Us | TafakoriTech",
    "seo_description": "Learn about our company and mission."
  }'

# 2. Publish (use content_id from response above)
curl -s -X POST http://127.0.0.1:8092/api/v1/publish/3 \
  -H "X-API-Key: your-secret-key"

# 3. Read (public)
curl -s http://127.0.0.1:8092/api/v1/pages/about-us
```

### Create and publish a blog post

```bash
# 1. Create
curl -s -X POST http://127.0.0.1:8092/api/v1/posts \
  -H "X-API-Key: your-secret-key" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Getting Started with Go Microservices",
    "body": "<p>In this post we explore...</p>",
    "excerpt": "A practical intro to Go microservices.",
    "category_id": 1,
    "tags": ["go", "docker", "api"]
  }'

# 2. Publish
curl -s -X POST http://127.0.0.1:8092/api/v1/publish/10 \
  -H "X-API-Key: your-secret-key"

# 3. List posts
curl -s "http://127.0.0.1:8092/api/v1/posts?page=1&limit=10"
```

### Create an article

```bash
curl -s -X POST http://127.0.0.1:8092/api/v1/articles \
  -H "X-API-Key: your-secret-key" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Clean Architecture in Go",
    "body": "<p>Clean architecture separates concerns...</p>",
    "excerpt": "How to structure a production Go service.",
    "tags": ["go", "ddd"],
    "seo_title": "Clean Architecture in Go | Herald",
    "seo_description": "A deep dive into structuring Go microservices."
  }'
```

### Search

```bash
curl -s "http://127.0.0.1:8092/api/v1/search?q=go+microservices&limit=5"
```

### Via Kong gateway

Replace `127.0.0.1:8092` with `127.0.0.1:8080/herald`:

```bash
curl -s http://127.0.0.1:8080/herald/api/v1/posts
curl -s http://127.0.0.1:8080/herald/api/v1/search?q=go
```

---

## Development

```bash
make run          # run locally with hot-reloadable env
make test         # run unit tests with race detector
make test-cover   # generate HTML coverage report
make swagger      # regenerate docs/swagger.json from annotations
make lint         # run golangci-lint
make docker-build # build Docker image
```

### Regenerating Swagger docs

```bash
go install github.com/swaggo/swag/cmd/swag@latest
make swagger
```

The `docs/swagger.json` is embedded into the binary at compile time and served at `GET /openapi.json`.  
It is also available through Kong's Swagger UI at `http://127.0.0.1:8080/swagger`.
