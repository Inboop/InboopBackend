# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Inboop** is an enterprise lead generation and management system that captures and analyzes Instagram DM conversations using AI-powered classification. The backend receives real-time Instagram DM notifications via Meta webhooks, uses AI to detect language and classify message intent, and organizes conversations into trackable leads.

## Technology Stack

- **Framework**: Spring Boot 3.5.5
- **Language**: Java 17
- **Build Tool**: Maven (use `./mvnw` wrapper)
- **Database**: H2 (development), PostgreSQL (production on AWS RDS)
- **Migrations**: Flyway (runs as separate container before app)
- **Security**: Spring Security with OAuth2 (Google) and form-based authentication
- **Template Engine**: Thymeleaf (for server-side UI)

## Development Commands

### Build & Run
```bash
# Build the project
./mvnw clean install

# Run the application
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a specific test class
./mvnw test -Dtest=BackendApplicationTests

# Package for production
./mvnw clean package
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

### Database Access (Development)
- **H2 Console**: http://localhost:8080/h2-console
  - JDBC URL: `jdbc:h2:file:./data/inboop`
  - Username: `sa`
  - Password: `password`

### Database Migrations (Flyway)

Flyway manages all database schema changes. In production, migrations run as a separate Docker container before the app starts.

**Migration files:** `src/main/resources/db/migration/`

**Creating a new migration:**
1. Create a new SQL file with naming convention: `V{version}__{description}.sql`
2. Example: `V2__add_phone_to_users.sql`, `V3__create_products_table.sql`
3. Version numbers must be sequential and unique

**Example migration:**
```sql
-- V2__add_phone_to_users.sql
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
CREATE INDEX idx_users_phone ON users(phone);
```

**Important:**
- In development, `spring.flyway.enabled=false` and Hibernate `ddl-auto=update` manages schema
- In production, Flyway container runs first, then the app starts with `ddl-auto=validate`
- Flyway tracks applied migrations in `flyway_schema_history` table
- Never modify a migration file that has been applied - create a new one instead
- Use `IF NOT EXISTS` / `IF EXISTS` for idempotent migrations

### Application URLs
- **Base URL**: http://localhost:8080
- **API Base**: http://localhost:8080/api/v1
- **Health Check**: http://localhost:8080/actuator/health

## Architecture

This project follows **Domain-Driven Design (DDD)** with clear separation by business domain:

### Core Domain Structure

```
com.inboop.backend
├── auth/              Authentication & User Management (form + OAuth2)
├── business/          Business Account Management (Instagram accounts)
├── instagram/         Instagram Graph API integration & webhooks
├── lead/              Lead Management (core domain)
├── order/             Order Tracking (from inquiry to delivery)
├── meta/              Meta Platform Integration (data deletion callbacks)
├── ai/                AI/ML Services (language detection, intent classification)
├── notification/      Real-time Notifications (WebSocket)
├── analytics/         Dashboard Analytics
├── config/            Global Configuration (SecurityConfig)
└── shared/            Cross-cutting concerns (exceptions, DTOs, constants)
```

### Key Architectural Patterns

1. **Entity Relationships**:
   - `User` owns multiple `Business` accounts
   - `Business` has many `Lead` records
   - `Lead` contains multiple `Conversation` threads
   - `Conversation` has many `Message` entries
   - `Lead` can convert to `Order`

2. **Security Configuration** (`SecurityConfig.java`):
   - Webhook endpoints (`/api/v1/webhooks/**`) are public (required by Meta)
   - Meta callback endpoints (`/meta/**`) are public (verified via signed_request)
   - All other API endpoints require authentication
   - CSRF disabled for REST APIs
   - CORS configured via `app.cors.allowed-origins` property
   - BCrypt password encoding

3. **Data Flow** (Instagram DM → Lead):
   - Instagram DM arrives at `/api/v1/webhooks/instagram` (POST)
   - Webhook controller logs the message (processing not yet implemented)
   - Future: Queue message → AI classification → Create/Update Lead → WebSocket notification

## Environment Configuration

The application uses Spring Boot's externalized configuration with environment variables. Key variables:

### Required for Development
```bash
# Database (H2 default)
DB_URL=jdbc:h2:file:./data/inboop
DB_DRIVER=org.h2.Driver
DB_USER=sa
DB_PASSWORD=password
DB_PLATFORM=org.hibernate.dialect.H2Dialect

# Instagram Webhook
INSTAGRAM_WEBHOOK_VERIFY_TOKEN=inboop_verify_token
```

### Optional for OAuth
```bash
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
BACKEND_URL=http://localhost:8080
```

### Production (PostgreSQL)
```bash
DB_URL=jdbc:postgresql://localhost:5432/inboop
DB_DRIVER=org.postgresql.Driver
DB_PLATFORM=org.hibernate.dialect.PostgreSQLDialect
DDL_AUTO=validate
H2_CONSOLE_ENABLED=false
```

## Core Entities

### Lead Entity
- **Purpose**: Represents a customer lead from Instagram DM
- **Key Fields**:
  - `instagramUserId`, `instagramUsername`: Customer identifiers
  - `status`: LeadStatus enum (NEW, CONTACTED, QUALIFIED, CONVERTED, LOST, etc.)
  - `type`: LeadType enum (INQUIRY, ORDER_REQUEST, SUPPORT, COMPLAINT, etc.)
  - `assignedTo`: User assignment for team collaboration
  - `labels`: Flexible tagging system
  - `detectedLanguage`: AI-detected language
  - `lastMessageAt`: For sorting and prioritization

### Business Entity
- **Purpose**: Represents Instagram business accounts
- **Key Fields**:
  - `instagramBusinessId`, `instagramPageId`: Meta API identifiers
  - `accessToken`, `tokenExpiresAt`: Instagram Graph API credentials
  - `webhookVerified`: Webhook subscription status
  - `owner`: User who connected this account

### Conversation & Message
- **Purpose**: Thread of messages between business and customer
- Supports language detection and AI sentiment analysis
- Future: Translation support for multi-language conversations

## Instagram Webhook Integration

### Webhook Verification (GET)
Meta requires webhook endpoint verification:
- Endpoint: `GET /api/v1/webhooks/instagram`
- Validates `hub.verify_token` against `instagram.webhook.verify-token` property
- Returns `hub.challenge` on success

### Webhook Event Handling (POST)
- Endpoint: `POST /api/v1/webhooks/instagram`
- Receives Instagram DM notifications
- Currently logs events (processing TODO)
- Always returns 200 OK (Meta requirement)

### Implementation Status
- Webhook verification: ✅ Implemented
- Message logging: ✅ Implemented
- AI processing: ❌ Not implemented
- Lead creation: ❌ Not implemented
- WebSocket notifications: ❌ Not implemented

## Meta User Data Deletion (App Review Required)

Implementation for Meta's User Data Deletion requirements. Required for Meta App Review.

### Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/meta/data-deletion` | POST | Data Deletion Callback URL - receives deletion requests from Meta |
| `/meta/deauthorize` | POST | Deauthorization Callback URL - triggered when user removes app |
| `/meta/data-deletion-status` | GET | Status page for users to check deletion progress |

### Configuration

```bash
# Required in production
META_APP_SECRET=your_meta_app_secret    # From Meta for Developers > App Settings > Basic
APP_BASE_URL=https://inboop.com         # Used in status URL returned to Meta
```

### Meta for Developers Setup

1. Go to Meta for Developers > Your App > Settings > Basic
2. Set **Data Deletion Callback URL**: `https://api.inboop.com/meta/data-deletion`
3. Set **Deauthorization Callback URL**: `https://api.inboop.com/meta/deauthorize`

### Data Handling

**Deleted:** Messages (DM content), Leads (customer PII), Conversations (customer handles)
**Anonymized:** Orders (keep for accounting, remove PII), Business (remove tokens, mark inactive)
**Retained:** Aggregated analytics (no PII), deletion audit records

### Documentation

Full documentation: `docs/META-DATA-DELETION.md`

## Current Development Phase

**Phase 1: Foundation** ✅ Complete
- Domain-driven architecture
- Core entities (User, Business, Lead, Conversation, Message, Order)
- Authentication system (form + OAuth2)
- Instagram webhook endpoint
- Global exception handling

**Phase 2: Core Features** 🚧 In Progress
- Lead service and REST API (not yet implemented)
- Instagram Graph API integration (not yet implemented)
- AI service integration (planned: OpenAI)
- WebSocket notifications (not yet implemented)
- Business account management (not yet implemented)

## Security Notes

- OAuth2 redirect URI: `${BACKEND_URL}/login/oauth2/code/google`
- Session-based authentication (default Spring Security)
- Password encoding: BCrypt
- Webhook endpoints must remain public (Meta requires direct access)
- Production: Enable HTTPS, set `DDL_AUTO=validate`, disable H2 console

## Deployment

### Docker
```bash
# Build and run with Docker Compose (includes PostgreSQL)
docker-compose up

# Build Docker image manually
docker build -t inboop-backend .
```

### AWS (ECS Fargate + RDS PostgreSQL)
- Comprehensive deployment guide: `AWS-DEPLOYMENT.md`
- Quick start guide: `QUICKSTART-AWS.md`
- Infrastructure: ALB → ECS Fargate → RDS PostgreSQL
- Secrets stored in AWS Secrets Manager
- Logs in CloudWatch: `/ecs/inboop-backend`

## Git Commit Guidelines

**Commit Message Format**: Always use single-line commit messages. Keep them concise and descriptive.

Examples:
- ✅ `Add user authentication endpoint`
- ✅ `Fix null pointer exception in lead service`
- ✅ `Update database schema for order tracking`
- ❌ Multi-line commits with decorative footers

## Common Patterns

### Exception Handling
- Custom exceptions: `ResourceNotFoundException`, `BadRequestException`, `UnauthorizedException`
- Global handler: `GlobalExceptionHandler` returns standardized `ApiResponse`
- Always use specific exceptions, not generic `RuntimeException`

### API Response Format
Use `ApiResponse<T>` for all REST endpoints:
```java
return ResponseEntity.ok(ApiResponse.success(data));
return ResponseEntity.badRequest().body(ApiResponse.error("Error message"));
```

### Pagination
Use `PageResponse<T>` for paginated list endpoints (when implemented)

### Entity Timestamps
All entities use `@PrePersist` and `@PreUpdate` for automatic timestamp management

## Frontend Integration

This backend expects a separate frontend (React/Next.js recommended):
- **Frontend location**: `/Users/sudireddy/Projects/Inboop/inboop-frontend`
- **Frontend URL**: http://localhost:3000 (always use port 3000)
- API endpoint: `http://localhost:8080/api/v1`
- WebSocket endpoint (future): `ws://localhost:8080/ws`
- CORS origins configured via `ALLOWED_ORIGINS` env var
- Default allowed: `http://localhost:3000,http://localhost:3001`

### Running Frontend Locally
```bash
cd /Users/sudireddy/Projects/Inboop/inboop-frontend
npm run dev -- -p 3000
```

**Important**: Always run the frontend on port 3000. Kill any existing processes on that port before starting:
```bash
lsof -ti:3000 | xargs kill -9 2>/dev/null; npm run dev -- -p 3000
```

## Known TODOs in Code

1. **Instagram Webhook**: Asynchronous message processing with message queue
2. **Lead Service**: REST API implementation (GET, POST, PUT, PATCH)
3. **AI Integration**: Language detection and intent classification
4. **Business Management**: Instagram account connection flow
5. **Analytics**: Dashboard metrics calculation
6. **Order Management**: Order tracking REST API

## Production Architecture

### URL Structure
| URL | Purpose |
|-----|---------|
| `https://inboop.com` | Marketing/landing pages, login, register |
| `https://app.inboop.com` | Authenticated app (inbox, leads, orders, analytics, settings) |
| `https://api.inboop.com` | Backend API |

### Authentication Flow
- **Methods**: Google OAuth + Email/Password
- **Token Storage**: JWT tokens in localStorage
- **Cross-subdomain Auth**: Tokens passed via URL params from `inboop.com` to `app.inboop.com` after login
- **Google Client ID**: `1066939574972-1tivkefdbrr37rfjnrshm8edu8smvged.apps.googleusercontent.com`

### Infrastructure

#### Backend (EC2)
- **Location**: `/opt/inboop` on EC2
- **Deployment**: GitHub Actions → ECR → EC2 (via `docker-compose.prod.yml`)
- **CI/CD**: `.github/workflows/deploy.yml` triggers on push to `master`
- **Reverse Proxy**: nginx at `api.inboop.com` → `localhost:8080`
- **SSL**: Let's Encrypt (certbot)
- **Database**: RDS PostgreSQL (`inboop-db.cxoy0cum6qub.us-east-2.rds.amazonaws.com`)
- **Secrets**: AWS Secrets Manager (`rds!db-15d58b7f-937d-4e4b-b988-3b474cf662c6`)

#### Frontend (Hostinger VPS)
- **Location**: `/var/www/inboop-frontend`
- **Framework**: Next.js
- **Process Manager**: pm2
- **SSL**: Let's Encrypt for both `inboop.com` and `app.inboop.com`

### Key Frontend Files for Auth
- `middleware.ts` - Routes users to correct subdomain based on URL
- `contexts/AuthContext.tsx` - Cross-subdomain token handoff, login/logout logic
- `app/(app)/layout.tsx` - Auth protection for app routes

### Production Environment Variables

#### Backend (.env at /opt/inboop)
```bash
ALLOWED_ORIGINS=https://inboop.com,https://app.inboop.com,http://localhost:3000,http://localhost:3001
GOOGLE_CLIENT_ID=<from-google-cloud-console>
GOOGLE_CLIENT_SECRET=<from-aws-secrets-manager>
# For Flyway migrations (separate variables for host/name)
DB_HOST=inboop-db.cxoy0cum6qub.us-east-2.rds.amazonaws.com
DB_NAME=postgres
# For Spring Boot app (full JDBC URL)
DB_URL=jdbc:postgresql://inboop-db.cxoy0cum6qub.us-east-2.rds.amazonaws.com:5432/postgres
DB_USER=inboop_admin
DB_PASSWORD=<from-aws-secrets-manager>
JWT_SECRET=<secure-random-string>
```

#### Frontend (.env.production)
```bash
NEXT_PUBLIC_API_URL=https://api.inboop.com
NEXT_PUBLIC_GOOGLE_CLIENT_ID=1066939574972-1tivkefdbrr37rfjnrshm8edu8smvged.apps.googleusercontent.com
```

### Deployment Commands

#### Backend (automatic via CI/CD, or manual)
```bash
# On EC2 at /opt/inboop
git pull origin master
aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin 058264276362.dkr.ecr.us-east-2.amazonaws.com
docker pull 058264276362.dkr.ecr.us-east-2.amazonaws.com/inboop-backend:latest
docker-compose -f docker-compose.prod.yml down
docker-compose -f docker-compose.prod.yml up -d
```

#### Frontend (on Hostinger VPS)
```bash
cd /var/www/inboop-frontend
git pull origin master
npm install
npm run build
pm2 restart all
```

### CORS Configuration
- CORS is handled **only by Spring Boot** (not nginx) to avoid duplicate headers
- nginx config at `/etc/nginx/conf.d/api.inboop.com.conf` is a simple proxy without CORS headers

### Troubleshooting

#### "Failed to fetch" errors
1. Check if backend is running: `curl -I https://api.inboop.com/actuator/health`
2. Check CORS headers in response
3. Verify `ALLOWED_ORIGINS` in EC2 `.env` includes the frontend domain

#### Duplicate CORS headers
- Remove CORS headers from nginx config; let Spring Boot handle it

#### Cross-subdomain auth not working
- Tokens are passed via URL params (`?token=...&refresh=...`)
- Check `AuthContext.tsx` extracts tokens from URL on page load

## Instagram OAuth Integration

### Overview
Instagram Business account connection uses Facebook OAuth (since Instagram API is part of Meta's Graph API).

### OAuth Flow
1. User clicks "Connect Instagram" in Settings → Integrations
2. Frontend redirects to `GET /api/v1/instagram/oauth/authorize`
3. Backend redirects to Facebook OAuth dialog
4. User authorizes the app on Facebook
5. Facebook redirects to `GET /login/oauth2/code/facebook` with auth code
6. Backend exchanges code for access token
7. Backend redirects to frontend with token: `https://app.inboop.com/settings?success=true&token=xxx`

### Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/instagram/oauth/authorize` | GET | Initiates OAuth flow, redirects to Facebook |
| `/api/v1/instagram/oauth/status` | GET | Returns `{ configured: boolean, redirectUri: string }` |
| `/login/oauth2/code/facebook` | GET | OAuth callback, exchanges code for token |

### Configuration

```bash
# In .env - Use values from Meta Developer Console → Settings → Basic
FACEBOOK_APP_ID=<app_id_from_settings_basic>        # NOT the Instagram App ID
FACEBOOK_APP_SECRET=<app_secret_from_settings_basic>
```

**Important**: Use the App ID and App Secret from **Settings → Basic** in Meta Developer Console, not the Instagram-specific App ID shown in the Instagram API section.

### Meta Developer Console Setup

1. **Settings → Basic**:
   - App Domains: `inboop.com`, `app.inboop.com`, `api.inboop.com`
   - Add Platform → Website → Site URL: `https://app.inboop.com`

2. **Instagram → API setup with Instagram Business Login**:
   - Valid OAuth Redirect URIs: `https://api.inboop.com/login/oauth2/code/facebook`

3. **Permissions** (request in App Review):
   - `instagram_business_basic`
   - `instagram_business_manage_messages`
   - `instagram_manage_comments`

### Scopes
The OAuth request uses these scopes (configured in `application.properties`):
```
instagram_business_basic,instagram_business_manage_messages,instagram_manage_comments
```

### Testing
- App must be in **Live Mode** for public use, or users must be added as **Testers** in App Roles
- Users must have an **Instagram Business** or **Creator** account linked to a Facebook Page
- Clear browser cache if getting "Can't load URL" errors after configuration changes

### Key Files
- `instagram/controller/InstagramConnectionController.java` - OAuth initiation with one-time tokens
- `instagram/handler/FacebookOAuth2SuccessHandler.java` - OAuth callback processing
- `instagram/service/InstagramBusinessService.java` - Fetches Pages and IG accounts from Graph API
- `instagram/service/InstagramIntegrationCheckService.java` - Real-time status verification
- `config/FacebookOAuth2AuthorizationRequestResolver.java` - Adds config_id and auth_type params
- `instagram/util/SecureCookieUtil.java` - HMAC-SHA256 cookie signing
- `application.properties` - OAuth configuration

### Full Documentation
See `docs/FACEBOOK-OAUTH-FLOW.md` for complete OAuth flow documentation with diagrams.

## Instagram Integration Status API

The integration status endpoint performs real-time checks against Meta's Graph API.

### Endpoint
```
GET /api/v1/integrations/instagram/status
Authorization: Bearer <JWT>
```

### Response Structure
```json
{
  "status": "CONNECTED_READY | BLOCKED | NOT_CONNECTED | PENDING",
  "reason": "NO_PAGES_FOUND | IG_NOT_LINKED_TO_PAGE | TOKEN_EXPIRED | ...",
  "message": "User-friendly message",
  "retryAt": "ISO8601 timestamp (for ADMIN_COOLDOWN)",
  "details": { "instagramUsername": "...", "businessName": "..." },
  "actions": {
    "reconnectUrl": "/oauth2/authorization/facebook",
    "businessSettingsUrl": "https://business.facebook.com/settings",
    "businessSuiteUrl": "https://business.facebook.com/latest/home",
    "pageCreateUrl": "https://www.facebook.com/pages/create"
  }
}
```

### Status Values
| Status | Description |
|--------|-------------|
| `NOT_CONNECTED` | User never started OAuth or no token stored |
| `CONNECTED_READY` | Instagram account connected and ready for DMs |
| `BLOCKED` | Action required - see `reason` field |
| `PENDING` | Processing (rarely used) |

### Reason Codes (when BLOCKED)
| Reason | User Action |
|--------|-------------|
| `NO_PAGES_FOUND` | Create a Facebook Page |
| `IG_NOT_LINKED_TO_PAGE` | Link Instagram to Facebook Page |
| `IG_NOT_BUSINESS` | Convert to Business/Creator account |
| `OWNERSHIP_MISMATCH` | Check Business Manager permissions |
| `ADMIN_COOLDOWN` | Wait 7 days (Meta requirement for new admins) |
| `MISSING_PERMISSIONS` | Reconnect and approve all permissions |
| `TOKEN_EXPIRED` | Reconnect account |
| `API_ERROR` | Retry later |

### Caching Behavior
- Returns cached BLOCKED status if cooldown is active (no API call)
- Returns cached CONNECTED_READY if verified within 5 minutes
- Otherwise performs fresh API calls to Meta

### Constants
URL constants are centralized in `AppConstants.java`:
- `OAUTH_FACEBOOK_PATH` - OAuth start path
- `META_BUSINESS_SETTINGS_URL` - Business settings link
- `META_BUSINESS_SUITE_URL` - Business suite link
- `META_PAGE_CREATE_URL` - Create page link

## Frontend Integration Notes

### Settings Page (`app/(app)/settings/page.tsx`)
- Instagram integration uses a wizard modal for BLOCKED states
- WhatsApp and Facebook Messenger show "Coming Soon" (not implemented)
- Status is fetched on mount and after OAuth callback
- Uses `actions` object from API for all external URLs (not hardcoded)

### OAuth Flow Frontend
1. `POST /api/v1/integrations/instagram/connect` - Get one-time token
2. Redirect to `redirectUrl` from response
3. After OAuth callback, check URL params for `instagram_connected=true`
4. Call status endpoint to verify actual connection state
