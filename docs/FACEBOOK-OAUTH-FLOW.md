# Facebook/Instagram OAuth Flow Documentation

This document describes the complete Facebook/Instagram OAuth2 implementation for connecting Instagram Business accounts to Inboop.

## Table of Contents

1. [Overview](#overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Sequence Diagram](#sequence-diagram)
4. [Components](#components)
5. [Security Features](#security-features)
6. [API Reference](#api-reference)
7. [Error Handling](#error-handling)
8. [Configuration](#configuration)
9. [Frontend Integration](#frontend-integration)

---

## Overview

Inboop uses Facebook OAuth2 to connect Instagram Business accounts. This is required because Instagram's Messaging API is part of Meta's Graph API ecosystem, which uses Facebook authentication.

### Why Facebook OAuth for Instagram?

- Instagram Business accounts must be linked to a Facebook Page
- The Instagram Graph API requires a Page Access Token
- Meta's Business Login flow grants permissions for both platforms

### Key Design Decisions

1. **Server-side token storage**: Access tokens are never exposed to the frontend
2. **One-time connection tokens**: Prevents replay attacks during OAuth handoff
3. **Signed cookies**: HMAC-SHA256 signed cookies link OAuth callback to originating user
4. **Real-time verification**: Status checks perform live API calls to verify permissions

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              INBOOP SYSTEM                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────┐         ┌──────────────────────────────────────────────┐ │
│  │   Frontend   │         │                  Backend                      │ │
│  │  (Next.js)   │         │              (Spring Boot)                    │ │
│  │              │         │                                               │ │
│  │ ┌──────────┐ │  JWT    │ ┌─────────────────┐  ┌────────────────────┐  │ │
│  │ │ Settings │─┼────────►│ │ Connection      │  │ Security Config    │  │ │
│  │ │   Page   │ │         │ │ Controller      │  │ - JWT Filter       │  │ │
│  │ └──────────┘ │         │ │                 │  │ - OAuth2 Config    │  │ │
│  │      │       │         │ │ POST /connect   │  │ - CORS             │  │ │
│  │      │       │         │ └────────┬────────┘  └────────────────────┘  │ │
│  │      │       │         │          │                                    │ │
│  │      ▼       │         │          ▼                                    │ │
│  │ ┌──────────┐ │         │ ┌─────────────────┐  ┌────────────────────┐  │ │
│  │ │ Redirect │─┼────────►│ │ OAuth Start     │  │ Auth Request       │  │ │
│  │ │ to OAuth │ │         │ │ Endpoint        │  │ Resolver           │  │ │
│  │ └──────────┘ │         │ │                 │  │ - Adds config_id   │  │ │
│  │              │         │ │ GET /start      │  │ - Adds auth_type   │  │ │
│  │              │         │ └────────┬────────┘  └────────────────────┘  │ │
│  │              │         │          │                                    │ │
│  │              │         │          ▼                                    │ │
│  │              │         │ ┌─────────────────┐  ┌────────────────────┐  │ │
│  │              │         │ │ Success Handler │  │ Business Service   │  │ │
│  │              │◄────────┼─│                 │  │ - Fetch Pages      │  │ │
│  │              │         │ │ Stores tokens   │  │ - Fetch IG Account │  │ │
│  │              │         │ │ Creates Business│  │ - Store mappings   │  │ │
│  │              │         │ └─────────────────┘  └────────────────────┘  │ │
│  │              │         │                                               │ │
│  │ ┌──────────┐ │  JWT    │ ┌─────────────────┐  ┌────────────────────┐  │ │
│  │ │ Status   │─┼────────►│ │ Status Check    │  │ Database           │  │ │
│  │ │ Check    │ │         │ │ Service         │  │ - Users            │  │ │
│  │ └──────────┘ │         │ │ - Live API call │  │ - Businesses       │  │ │
│  │              │         │ │ - Cache (5 min) │  │ - Access Tokens    │  │ │
│  │              │         │ └─────────────────┘  └────────────────────┘  │ │
│  │              │         │                                               │ │
│  └──────────────┘         └──────────────────────────────────────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ HTTPS
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           META PLATFORM                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────────┐  │
│  │  OAuth Dialog    │    │  Token Endpoint  │    │  Graph API           │  │
│  │                  │    │                  │    │                      │  │
│  │ /dialog/oauth    │    │ /oauth/          │    │ /me/accounts         │  │
│  │                  │    │ access_token     │    │ /{page-id}           │  │
│  │ User authorizes  │    │                  │    │ /{ig-id}             │  │
│  │ permissions      │    │ Exchanges code   │    │                      │  │
│  │                  │    │ for token        │    │ Returns Pages &      │  │
│  └──────────────────┘    └──────────────────┘    │ Instagram accounts   │  │
│                                                   └──────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Sequence Diagram

```
┌──────────┐     ┌──────────┐     ┌───────────────┐     ┌──────────────┐     ┌─────────────┐
│ Frontend │     │ Backend  │     │ Spring OAuth2 │     │ Meta OAuth   │     │ Graph API   │
└────┬─────┘     └────┬─────┘     └───────┬───────┘     └──────┬───────┘     └──────┬──────┘
     │                │                   │                    │                    │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                    PHASE 1: INITIATE CONNECTION                              │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                │                   │                    │                    │
     │  POST /api/v1/integrations/instagram/connect            │                    │
     │  Authorization: Bearer <JWT>       │                    │                    │
     │ ──────────────►│                   │                    │                    │
     │                │                   │                    │                    │
     │                │ Validate JWT      │                    │                    │
     │                │ Generate one-time token (5 min TTL)    │                    │
     │                │ Store in memory   │                    │                    │
     │                │                   │                    │                    │
     │  { token, redirectUrl }            │                    │                    │
     │ ◄──────────────│                   │                    │                    │
     │                │                   │                    │                    │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                    PHASE 2: START OAUTH FLOW                                 │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                │                   │                    │                    │
     │  GET /instagram/connect/start?token=xxx                 │                    │
     │ ──────────────►│                   │                    │                    │
     │                │                   │                    │                    │
     │                │ Validate & consume one-time token      │                    │
     │                │ Create signed cookie (userId:timestamp)│                    │
     │                │ Set-Cookie: inboop_ig_connect=<signed> │                    │
     │                │                   │                    │                    │
     │  302 Redirect to /oauth2/authorization/facebook         │                    │
     │ ◄──────────────│                   │                    │                    │
     │                │                   │                    │                    │
     │  GET /oauth2/authorization/facebook                     │                    │
     │ ─────────────────────────────────►│                    │                    │
     │                │                   │                    │                    │
     │                │                   │ Resolve authorization request           │
     │                │                   │ Add config_id parameter                 │
     │                │                   │ Add auth_type=rerequest                 │
     │                │                   │                    │                    │
     │  302 Redirect to Meta OAuth Dialog │                    │                    │
     │ ◄─────────────────────────────────│                    │                    │
     │                │                   │                    │                    │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                    PHASE 3: USER AUTHORIZATION                               │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                │                   │                    │                    │
     │  GET /dialog/oauth?client_id=...&config_id=...&auth_type=rerequest          │
     │ ───────────────────────────────────────────────────────►│                    │
     │                │                   │                    │                    │
     │                │                   │                    │ Show permissions   │
     │                │                   │                    │ dialog to user     │
     │                │                   │                    │                    │
     │                │                   │     User clicks "Allow"                 │
     │                │                   │                    │                    │
     │  302 Redirect to /login/oauth2/code/facebook?code=xxx&state=yyy             │
     │ ◄───────────────────────────────────────────────────────│                    │
     │                │                   │                    │                    │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                    PHASE 4: TOKEN EXCHANGE                                   │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                │                   │                    │                    │
     │  GET /login/oauth2/code/facebook?code=xxx&state=yyy     │                    │
     │ ─────────────────────────────────►│                    │                    │
     │                │                   │                    │                    │
     │                │                   │ Validate state (CSRF)                   │
     │                │                   │                    │                    │
     │                │                   │  POST /oauth/access_token               │
     │                │                   │  { code, client_id, client_secret }     │
     │                │                   │ ──────────────────►│                    │
     │                │                   │                    │                    │
     │                │                   │  { access_token, expires_in }           │
     │                │                   │ ◄──────────────────│                    │
     │                │                   │                    │                    │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                    PHASE 5: FETCH INSTAGRAM ACCOUNTS                         │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                │                   │                    │                    │
     │                │ Success Handler invoked                │                    │
     │                │◄──────────────────│                    │                    │
     │                │                   │                    │                    │
     │                │ Read signed cookie                     │                    │
     │                │ Verify signature & extract userId      │                    │
     │                │ Fetch User from database               │                    │
     │                │                   │                    │                    │
     │                │  GET /me/accounts?access_token=xxx     │                    │
     │                │ ──────────────────────────────────────────────────────────►│
     │                │                   │                    │                    │
     │                │  { data: [{ id: "page1", name: "..." }] }                   │
     │                │ ◄──────────────────────────────────────────────────────────│
     │                │                   │                    │                    │
     │                │  GET /{page-id}?fields=instagram_business_account          │
     │                │ ──────────────────────────────────────────────────────────►│
     │                │                   │                    │                    │
     │                │  { instagram_business_account: { id: "ig123" } }           │
     │                │ ◄──────────────────────────────────────────────────────────│
     │                │                   │                    │                    │
     │                │  GET /{ig-id}?fields=username,name     │                    │
     │                │ ──────────────────────────────────────────────────────────►│
     │                │                   │                    │                    │
     │                │  { username: "mybusiness", name: "My Business" }           │
     │                │ ◄──────────────────────────────────────────────────────────│
     │                │                   │                    │                    │
     │                │ Create/Update Business entity          │                    │
     │                │ Store access_token in database         │                    │
     │                │ Clear connection cookie                │                    │
     │                │                   │                    │                    │
     │  302 Redirect to frontend/settings?instagram_connected=true                 │
     │ ◄──────────────│                   │                    │                    │
     │                │                   │                    │                    │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                    PHASE 6: STATUS VERIFICATION                              │
     │ ══════════════════════════════════════════════════════════════════════════════════
     │                │                   │                    │                    │
     │  GET /api/v1/integrations/instagram/status              │                    │
     │  Authorization: Bearer <JWT>       │                    │                    │
     │ ──────────────►│                   │                    │                    │
     │                │                   │                    │                    │
     │                │ Check cooldown (return cached if active)                   │
     │                │ Check status cache (return if <5 min old)                  │
     │                │                   │                    │                    │
     │                │  GET /me/accounts │                    │                    │
     │                │ ──────────────────────────────────────────────────────────►│
     │                │                   │                    │                    │
     │                │  { data: [...] }  │                    │                    │
     │                │ ◄──────────────────────────────────────────────────────────│
     │                │                   │                    │                    │
     │                │ Verify pages accessible                │                    │
     │                │ Verify IG account linked               │                    │
     │                │ Update cache timestamp                 │                    │
     │                │                   │                    │                    │
     │  { status: "CONNECTED_READY", details: {...}, actions: {...} }              │
     │ ◄──────────────│                   │                    │                    │
     │                │                   │                    │                    │
     ▼                ▼                   ▼                    ▼                    ▼
```

---

## Components

### 1. InstagramConnectionController

**Location:** `src/main/java/com/inboop/backend/instagram/controller/InstagramConnectionController.java`

Handles the OAuth initiation and callback routing.

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/api/v1/integrations/instagram/connect` | POST | JWT | Generate one-time token, return redirect URL |
| `/instagram/connect/start` | GET | Public | Consume token, set cookie, redirect to OAuth |
| `/api/v1/integrations/instagram/status` | GET | JWT | Real-time status check with Meta API |
| `/api/v1/integrations/instagram/status/basic` | GET | JWT | Database-only status (no API call) |
| `/api/v1/integrations/instagram/disconnect` | DELETE | JWT | Disconnect all accounts |
| `/api/v1/integrations/instagram/disconnect/{id}` | DELETE | JWT | Disconnect specific account |

### 2. FacebookOAuth2AuthorizationRequestResolver

**Location:** `src/main/java/com/inboop/backend/config/FacebookOAuth2AuthorizationRequestResolver.java`

Customizes the OAuth authorization request by adding Meta-specific parameters:

```java
additionalParameters.put("config_id", facebookConfigId);  // Instagram Business Login config
additionalParameters.put("auth_type", "rerequest");       // Force re-prompt for declined permissions
```

### 3. FacebookOAuth2SuccessHandler

**Location:** `src/main/java/com/inboop/backend/instagram/handler/FacebookOAuth2SuccessHandler.java`

Processes successful OAuth callbacks:

1. Validates signed cookie to identify user
2. Retrieves access token from Spring Security
3. Calls `InstagramBusinessService` to fetch and store account mappings
4. Clears connection cookie
5. Redirects to frontend with success/error status

### 4. InstagramBusinessService

**Location:** `src/main/java/com/inboop/backend/instagram/service/InstagramBusinessService.java`

Handles Facebook Graph API interactions:

- Fetches user's Facebook Pages via `GET /me/accounts`
- For each page, fetches linked Instagram Business Account
- Creates/updates `Business` entities with account mappings

### 5. InstagramIntegrationCheckService

**Location:** `src/main/java/com/inboop/backend/instagram/service/InstagramIntegrationCheckService.java`

Performs real-time integration verification:

- Checks token validity
- Verifies page accessibility
- Detects permission issues
- Handles Meta's 7-day admin cooldown
- Caches successful status for 5 minutes

### 6. SecureCookieUtil

**Location:** `src/main/java/com/inboop/backend/instagram/util/SecureCookieUtil.java`

Provides HMAC-SHA256 signing for connection cookies:

```java
// Format: base64(value).base64(hmac)
sign("123:1702900000000") => "MTIzOjE3MDI5MDAw...XyZ9KL..."
verify(signed) => "123:1702900000000" or null
```

### 7. SecurityConfig

**Location:** `src/main/java/com/inboop/backend/config/SecurityConfig.java`

Configures Spring Security for OAuth2:

- Registers custom authorization request resolver
- Registers success handler
- Configures public vs protected endpoints
- Sets up JWT authentication filter

---

## Security Features

### 1. One-Time Connection Tokens

```
Frontend (authenticated) ──POST /connect──► Backend
                                            │
                                            ▼
                                    Generate token (UUID)
                                    Store: token → userId
                                    TTL: 5 minutes
                                            │
                         { token, redirectUrl } ◄──┘
                                    │
Frontend ──GET /start?token=xxx────►│
                                    │
                                    ▼
                            Validate & CONSUME token
                            (Cannot be reused)
```

**Why?** Prevents replay attacks. Each connection attempt requires a fresh token.

### 2. Signed Cookies

```
Cookie Value: userId:timestamp
              │
              ▼
        HMAC-SHA256(value, jwt.secret)
              │
              ▼
        base64(value).base64(signature)
```

**Security properties:**
- HttpOnly: JavaScript cannot read the cookie
- Secure: Only sent over HTTPS (production)
- SameSite=Lax: Mitigates CSRF
- 10-minute TTL: Limits attack window
- Signature verification: Detects tampering
- Constant-time comparison: Prevents timing attacks

### 3. Token Storage

```
┌─────────────┐                    ┌─────────────┐
│   Frontend  │                    │   Backend   │
│             │                    │             │
│ Never sees  │                    │ Stores in   │
│ access      │◄── Status only ───│ database    │
│ tokens      │                    │             │
└─────────────┘                    └─────────────┘
```

Access tokens are:
- Stored encrypted in database
- Never sent to frontend
- Never included in URLs
- Never logged (even at debug level)

### 4. CSRF Protection

Spring Security's OAuth2 implementation automatically:
- Generates random `state` parameter
- Validates state on callback
- Rejects mismatched states

### 5. Permission Re-request

Adding `auth_type=rerequest` ensures:
- Users see all permissions even if previously declined
- Reconnecting grants fresh permissions
- No stale permission state

---

## API Reference

### Initialize Connection

```http
POST /api/v1/integrations/instagram/connect
Authorization: Bearer <JWT_TOKEN>
```

**Response:**
```json
{
  "token": "abc123...",
  "redirectUrl": "/instagram/connect/start?token=abc123...",
  "expiresIn": 300
}
```

### Check Status (Real-time)

```http
GET /api/v1/integrations/instagram/status
Authorization: Bearer <JWT_TOKEN>
```

**Response (Connected):**
```json
{
  "status": "CONNECTED_READY",
  "message": "Instagram account connected and ready to receive DMs.",
  "details": {
    "instagramUsername": "mybusiness",
    "facebookPageId": "123456789",
    "businessName": "My Business Page"
  },
  "actions": {
    "reconnectUrl": "https://api.inboop.com/oauth2/authorization/facebook",
    "businessSettingsUrl": "https://business.facebook.com/settings",
    "businessSuiteUrl": "https://business.facebook.com/latest/home",
    "pageCreateUrl": "https://www.facebook.com/pages/create"
  }
}
```

**Response (Blocked):**
```json
{
  "status": "BLOCKED",
  "reason": "IG_NOT_LINKED_TO_PAGE",
  "message": "Instagram isn't fully set up yet. Please connect your Instagram account to your page.",
  "details": {
    "businessName": "My Business Page",
    "pagesChecked": 2
  },
  "nextActions": [
    {
      "type": "LINK",
      "label": "Connect Instagram",
      "url": "https://www.facebook.com/settings/?tab=linked_instagram"
    }
  ],
  "actions": {
    "reconnectUrl": "https://api.inboop.com/oauth2/authorization/facebook",
    "businessSettingsUrl": "https://business.facebook.com/settings"
  }
}
```

### Check Status (Basic)

```http
GET /api/v1/integrations/instagram/status/basic
Authorization: Bearer <JWT_TOKEN>
```

**Response:**
```json
{
  "connected": true,
  "configured": true,
  "count": 1,
  "instagramBusinessAccountId": "17841400000000000",
  "instagramUsername": "mybusiness"
}
```

### Disconnect

```http
DELETE /api/v1/integrations/instagram/disconnect
Authorization: Bearer <JWT_TOKEN>
```

**Response:**
```json
{
  "success": true,
  "message": "All Instagram accounts disconnected"
}
```

---

## Error Handling

### Status Reasons

| Reason | Description | User Action |
|--------|-------------|-------------|
| `NO_PAGES_FOUND` | User has no Facebook Pages | Create a Page |
| `IG_NOT_LINKED_TO_PAGE` | Page exists but Instagram not linked | Link Instagram in Page settings |
| `IG_NOT_BUSINESS` | Instagram is personal account | Convert to Business/Creator |
| `OWNERSHIP_MISMATCH` | Lost access to previously connected account | Check Business Manager permissions |
| `ADMIN_COOLDOWN` | Meta's 7-day wait for new admins | Wait until `retryAt` |
| `MISSING_PERMISSIONS` | OAuth permissions not granted | Reconnect and approve all |
| `TOKEN_EXPIRED` | Access token expired | Reconnect |
| `API_ERROR` | Unexpected Meta API error | Retry later |

### OAuth Errors

| Error Code | Description |
|------------|-------------|
| `session_expired` | Connection cookie expired or invalid |
| `user_not_found` | User ID from cookie not in database |
| `no_token` | OAuth completed but no token received |
| `no_instagram_accounts` | OAuth succeeded but no IG accounts found |
| `connection_failed` | Unexpected error during processing |
| `wrong_provider` | OAuth from unexpected provider |

---

## Configuration

### Environment Variables

```bash
# Meta App Credentials (from Meta Developer Console)
FACEBOOK_APP_ID=your_app_id
FACEBOOK_APP_SECRET=your_app_secret
META_FACEBOOK_CONFIG_ID=your_config_id  # Instagram Business Login config

# URLs
BACKEND_URL=https://api.inboop.com
FRONTEND_URL=https://app.inboop.com
ALLOWED_ORIGINS=https://inboop.com,https://app.inboop.com

# Security
JWT_SECRET=your_jwt_secret  # Also used for cookie signing
COOKIE_SECURE=true          # Set false for local development
```

### Meta Developer Console Setup

1. **Settings > Basic**
   - App Domains: `inboop.com`, `app.inboop.com`, `api.inboop.com`
   - Website > Site URL: `https://app.inboop.com`

2. **Instagram > API setup with Instagram Business Login**
   - Valid OAuth Redirect URIs: `https://api.inboop.com/login/oauth2/code/facebook`
   - Create a configuration and note the Config ID

3. **Permissions** (request in App Review)
   - `instagram_business_basic`
   - `instagram_business_manage_messages`
   - `instagram_manage_comments`

---

## Frontend Integration

### Step 1: Initialize Connection

```typescript
const connectInstagram = async () => {
  const token = localStorage.getItem('accessToken');

  const response = await fetch(`${API_URL}/api/v1/integrations/instagram/connect`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` }
  });

  const { redirectUrl } = await response.json();

  // Redirect to OAuth flow
  window.location.href = `${API_URL}${redirectUrl}`;
};
```

### Step 2: Handle OAuth Callback

```typescript
// In settings page useEffect
useEffect(() => {
  const params = new URLSearchParams(window.location.search);

  if (params.get('instagram_connected') === 'true') {
    // Check actual status
    fetchInstagramStatus();
  } else if (params.get('instagram_error')) {
    showError(params.get('instagram_error'));
  }
}, []);
```

### Step 3: Check Status

```typescript
const fetchInstagramStatus = async () => {
  const token = localStorage.getItem('accessToken');

  const response = await fetch(`${API_URL}/api/v1/integrations/instagram/status`, {
    headers: { 'Authorization': `Bearer ${token}` }
  });

  const status = await response.json();

  switch (status.status) {
    case 'CONNECTED_READY':
      showSuccess('Instagram connected!');
      break;
    case 'BLOCKED':
      showBlockedUI(status.reason, status.message, status.actions);
      break;
    case 'NOT_CONNECTED':
      showConnectButton();
      break;
  }
};
```

### Step 4: Handle Blocked States

```typescript
const handleBlockedState = (status: IntegrationStatusResponse) => {
  const { reason, actions } = status;

  switch (reason) {
    case 'TOKEN_EXPIRED':
    case 'MISSING_PERMISSIONS':
      // Reconnect flow
      window.location.href = actions.reconnectUrl;
      break;

    case 'NO_PAGES_FOUND':
      // External link
      window.open(actions.pageCreateUrl, '_blank');
      break;

    case 'IG_NOT_LINKED_TO_PAGE':
    case 'OWNERSHIP_MISMATCH':
      // External link
      window.open(actions.businessSettingsUrl, '_blank');
      break;

    case 'ADMIN_COOLDOWN':
      // Show wait message with retryAt date
      showWaitMessage(status.retryAt);
      break;
  }
};
```

---

## Related Files

| File | Purpose |
|------|---------|
| `SecurityConfig.java` | Spring Security configuration |
| `InstagramConnectionController.java` | OAuth initiation endpoints |
| `FacebookOAuth2AuthorizationRequestResolver.java` | Custom OAuth parameters |
| `FacebookOAuth2SuccessHandler.java` | OAuth callback processing |
| `InstagramBusinessService.java` | Graph API interactions |
| `InstagramIntegrationCheckService.java` | Status verification |
| `SecureCookieUtil.java` | Cookie signing utility |
| `Business.java` | Entity for storing account mappings |
| `IntegrationStatusResponse.java` | DTO for status responses |
| `AppConstants.java` | URL constants |

---

## Troubleshooting

### "Session Expired" Error
- Connection cookie expired (>10 minutes between starting and completing OAuth)
- Solution: Try connecting again

### "No Instagram Accounts Found"
- OAuth succeeded but no Instagram Business accounts linked to any Page
- Solution: Link Instagram to Facebook Page first

### Stuck in BLOCKED State
- Check the `reason` field for specific issue
- Use `actions` URLs to guide user to fix

### Token Expires Quickly
- Meta access tokens typically last 60 days
- Long-lived tokens can be exchanged if needed
- Status check detects expiration and prompts reconnect
