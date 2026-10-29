# Sentinel

Sentinel is an authentication and session management service that handles OTP-based authentication, JWT token management, and user logout functionality. It integrates with the Persona IAM service to manage user identities and provides secure token-based authentication for the TafakoriTech platform.

## Features

- **OTP Authentication** - One-Time Password request and verification flow
- **JWT Token Management** - Generate and refresh access/refresh token pairs with configurable TTL
- **Session Management** - Track and invalidate user sessions
- **Persona Integration** - Seamless integration with Persona IAM service for user verification
- **User Status Validation** - Verify user account is active before authentication
- **Token Refresh** - Secure token renewal without re-authentication
- **Logout Management** - Session invalidation on logout
- **PostgreSQL Backend** - Persistent storage for OTP records and session tokens
- **CORS Support** - Cross-origin resource sharing for web clients
- **OpenAPI Documentation** - Auto-generated API documentation with Swagger

## Architecture

Sentinel provides an authentication layer that bridges user management (via Persona) with session management:

```
internal/
├── handler/       # HTTP request handlers for auth endpoints
├── service/       # Authentication business logic
├── persona/       # Persona IAM client integration
├── model/         # Data models for OTP and sessions
├── config/        # Configuration management
└── db/            # Database connection management
```

## How It Works

### OTP-Based Authentication Flow

1. **Request OTP** - Client provides user identifier (email/username)
2. **Verify User** - Service checks Persona to verify user exists and is active
3. **Generate OTP** - One-time password is generated and stored with expiration
4. **Verify OTP** - Client submits OTP with identifier
5. **Issue Tokens** - On successful verification, service issues JWT access token and refresh token
6. **Session Created** - Session is tracked in database for logout management

### Token Refresh Flow

1. Client sends refresh token
2. Service validates refresh token in database
3. If valid and not expired, new token pair is issued
4. Old refresh token may be invalidated (depends on refresh strategy)

### Logout Flow

1. Client sends refresh key/token
2. Service invalidates the session in database
3. Refresh token becomes unusable

## API Endpoints

### Health Check
- `GET /health` - Service health check

### Authentication
- `POST /otp/request` - Request OTP for user authentication
- `POST /otp/verify` - Verify OTP and receive token pair
- `POST /refresh` - Refresh access token using refresh key
- `POST /logout` - Logout user and invalidate session

### Documentation
- `GET /openapi.json` - OpenAPI 3.0 specification

## Request/Response Examples

### Request OTP
```bash
POST /otp/request
Content-Type: application/json

{
  "identifier": "user@example.com"
}
```

Response:
```json
{
  "message": "OTP sent",
  "otp": "123456"
}
```

### Verify OTP
```bash
POST /otp/verify
Content-Type: application/json

{
  "identifier": "user@example.com",
  "otp": "123456"
}
```

Response:
```json
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "expires_in": 3600
}
```

### Refresh Token
```bash
POST /refresh
Content-Type: application/json

{
  "refresh_key": "eyJ..."
}
```

Response:
```json
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "expires_in": 3600
}
```

### Logout
```bash
POST /logout
Content-Type: application/json

{
  "refresh_key": "eyJ..."
}
```

Response:
```json
{
  "message": "logged out"
}
```

## Getting Started

### Prerequisites
- Go 1.22+
- PostgreSQL 12+
- Make
- Persona service running and accessible

### Environment Variables

Create a `.env` file:

```env
# Server Configuration
HOST=127.0.0.1
PORT=8081
ENV=development

# Database
DATABASE_URL=postgres://user:password@127.0.0.1:5432/sentinel

# Authentication
JWT_SECRET=your-secret-key
ACCESS_TOKEN_TTL=3600
REFRESH_TOKEN_TTL=604800

# Persona Service Integration
PERSONA_URL=http://127.0.0.1:8080
PERSONA_API_KEY=your-persona-api-key

# Swagger Documentation
SWAGGER_HOST=127.0.0.1:8081
SWAGGER_BASE_PATH=/
```

### Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `HOST` | Server bind address | `127.0.0.1` |
| `PORT` | Server port | `8081` |
| `ENV` | Environment (development/production) | `development` |
| `DATABASE_URL` | PostgreSQL connection string | Required |
| `JWT_SECRET` | Secret key for JWT signing | Required |
| `ACCESS_TOKEN_TTL` | Access token expiration in seconds | `3600` |
| `REFRESH_TOKEN_TTL` | Refresh token expiration in seconds | `604800` |
| `PERSONA_URL` | Persona service URL | Required |
| `PERSONA_API_KEY` | API key for Persona service calls | Required |
| `SWAGGER_HOST` | Swagger documentation host | Current request host |
| `SWAGGER_BASE_PATH` | Swagger documentation base path | `/` |

### Running the Service

```bash
# Install dependencies
go mod download

# Run database migrations
make migrate

# Start the service
make run
```

The service will be available at `http://127.0.0.1:8081`.

### Docker

```bash
docker build -t sentinel:latest .
docker run -p 8081:8081 --env-file .env sentinel:latest
```

## Database

Sentinel uses PostgreSQL with migration support. Migrations are stored in the `migrations/` directory.

Key tables:
- `otp_sessions` - Stores one-time passwords and their expiration
- `refresh_tokens` - Stores refresh token sessions and validity status
- `users` - User information (synced from Persona)

## Integration with Persona

Sentinel requires a running Persona service instance. On OTP request:

1. Sentinel calls Persona to verify user exists
2. Checks that user account is active
3. Returns appropriate error if user is inactive or not found

Make sure to configure `PERSONA_URL` and `PERSONA_API_KEY` correctly.

## Development

### Running Tests
```bash
make test
```

### Building Binary
```bash
make build
```

## Error Codes

| Error | Status | Meaning |
|-------|--------|---------|
| `user not registered` | 401 | User doesn't exist in Persona |
| `user account is inactive` | 403 | User account is disabled |
| `invalid or expired OTP` | 401 | OTP is incorrect or expired |
| `invalid or expired refresh key` | 401 | Refresh token is invalid or expired |

## Security Considerations

- OTPs are one-time use and expire after a short duration
- Refresh tokens are invalidated on logout
- All tokens are JWT-based with cryptographic signatures
- Database stores hashed OTP values (implementation dependent)
- CORS is enabled for all origins in default configuration (configure as needed)

## License

See LICENSE file for details.
