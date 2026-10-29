# Persona

Persona is an Identity and Access Management (IAM) service built with Go and Gin. It provides a robust API for managing users, roles, permissions, and teams with JWT-based authentication and API key authorization for service-to-service communication.

## Features

- **User Management** - Create, read, update, and delete users with support for authentication
- **Role-Based Access Control (RBAC)** - Define and manage roles with fine-grained permissions
- **Permission Management** - Create and assign permissions to roles
- **Team Management** - Organize users into teams with team-level access control
- **JWT Authentication** - Secure API endpoints with JWT token validation
- **API Key Authorization** - Service-to-service communication with API keys
- **PostgreSQL Backend** - Persistent storage with migrations support
- **Structured Logging** - Production-ready logging with Uber's Zap logger
- **CORS Support** - Cross-origin resource sharing enabled for browser-based clients
- **OpenAPI Documentation** - Auto-generated API documentation with Swagger

## Architecture

Persona follows a clean architecture pattern with clear separation of concerns:

```
internal/
├── user/          # User management domain
├── role/          # Role management domain
├── permission/    # Permission management domain
├── team/          # Team management domain
├── access/        # Access control logic
shared/
├── config/        # Configuration management
├── db/            # Database connection pooling
├── middleware/    # HTTP middleware (auth, logging, CORS)
```

Each domain module contains:
- `domain/` - Business logic models
- `handler/` - HTTP request handlers
- `service/` - Business logic and operations
- `repository/` - Data access layer (PostgreSQL)

## API Endpoints

### Health Check
- `GET /health` - Service health check (no authentication required)

### Users
- `POST /api/v1/users` - Create a new user
- `GET /api/v1/users` - List all users
- `GET /api/v1/users/:id` - Get user details
- `PUT /api/v1/users/:id` - Update user
- `DELETE /api/v1/users/:id` - Delete user

### Roles
- `POST /api/v1/roles` - Create a new role
- `GET /api/v1/roles` - List all roles
- `GET /api/v1/roles/:id` - Get role details
- `PUT /api/v1/roles/:id` - Update role
- `DELETE /api/v1/roles/:id` - Delete role

### Permissions
- `POST /api/v1/permissions` - Create a new permission
- `GET /api/v1/permissions` - List all permissions
- `GET /api/v1/permissions/:id` - Get permission details
- `PUT /api/v1/permissions/:id` - Update permission
- `DELETE /api/v1/permissions/:id` - Delete permission

### Teams
- `POST /api/v1/teams` - Create a new team
- `GET /api/v1/teams` - List all teams
- `GET /api/v1/teams/:id` - Get team details
- `PUT /api/v1/teams/:id` - Update team
- `DELETE /api/v1/teams/:id` - Delete team

### Internal Routes
- `/internal/*` - Service-to-service endpoints (API key required)

## Authentication

### JWT Authentication
All API endpoints (except `/health`) require a valid JWT token in the `Authorization` header:

```
Authorization: Bearer <jwt_token>
```

### API Key Authorization
Internal routes require an API key in the `Authorization` header:

```
Authorization: ApiKey <api_key>
```

## Getting Started

### Prerequisites
- Go 1.22+
- PostgreSQL 12+
- Make

### Environment Variables

Create a `.env` file in the project root:

```env
# Server Configuration
HOST=127.0.0.1
PORT=8080
ENV=development

# Database
DATABASE_URL=postgres://user:password@127.0.0.1:5432/persona

# Authentication
JWT_SECRET=your-secret-key
API_KEY=your-api-key

# Swagger Documentation
SWAGGER_HOST=127.0.0.1:8080
SWAGGER_BASE_PATH=/api/v1
```

### Running the Service

```bash
# Install dependencies
go mod download

# Run database migrations
make migrate

# Start the service
make run
```

The service will be available at `http://127.0.0.1:8080`.

### Docker

Build and run with Docker:

```bash
docker build -t persona:latest .
docker run -p 8080:8080 --env-file .env persona:latest
```

## Database

The service uses PostgreSQL with migration support. Migrations are stored in the `migrations/` directory.

To run migrations:

```bash
make migrate
```

## Development

### Running Tests
```bash
make test
```

### Building Binary
```bash
make build
```

### Code Structure
The project follows Go best practices with:
- Clear package organization
- Interface-based dependencies
- Repository pattern for data access
- Service layer for business logic
- HTTP handlers as thin request/response adapters

## Configuration

Configuration is loaded from environment variables with defaults. See the `shared/config` package for available options.

## Logging

Persona uses Uber's Zap logger for structured, production-ready logging:
- **Development mode** - Human-readable JSON with full stack traces
- **Production mode** - Optimized JSON output for log aggregation

## OpenAPI Documentation

The service exposes OpenAPI 3.0 specification at `/openapi.json`. Use Swagger UI or similar tools to explore the API interactively.

## Contributing

Follow Go conventions and ensure all tests pass before submitting changes.

## License

See LICENSE file for details.
