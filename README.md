# DevPilot

**AI-Powered Code Intelligence Workspace**

Connect a GitHub repository, index its source code, search semantically,
and ask grounded questions with real file and line references.

## Core features

- User authentication with BCrypt, JWT access tokens and rotating refresh tokens
- GitHub OAuth and public/private repositories accessible to the connected user
- Async repository ingestion, language-aware parsing and bounded code chunking
- OpenAI-compatible embeddings, PostgreSQL/pgvector semantic code search
- Grounded repository Q&A with validated source IDs and actual file/line references
- Prompt-injection-aware context handling and explicit insufficient-evidence responses
- Staged indexing and atomic publication for safe reindexing
- React workspace for repositories, indexing status, Search and Ask
- Dockerized full stack and GitHub Actions backend/frontend checks

## Architecture

```text
Browser: React / Nginx :5174
             | /api/* (same origin)
      Spring Boot API :8080 (host :8083)
             |
  +----------+----------+----------+---------+
  |   Auth   |  GitHub  | Indexing |   RAG   |
  +----------+----------+----------+---------+
             |                         |
   PostgreSQL 17 + pgvector      Embedding / LLM providers
      :5432 (host :5434)         (HTTPS calls from backend)

GitHub repository -> Trees/Blobs API -> filtering -> parsing/chunking
  -> embeddings -> pgvector -> semantic retrieval -> bounded context
  -> grounded LLM response -> source ID validation -> source-backed answer
```

The backend owns authorization and every repository query is scoped to its user.
Files, chunks and embeddings are staged per indexing job, then published together in
one transaction. A failed reindex preserves the previous published snapshot. RAG
requires READY and rejects an index generation change during a request; semantic
retrieval can use a complete prior snapshot during indexing/failure.

Java parsing uses the JDK compiler API **only to parse**, with annotation processing
disabled. Repository code is never compiled or executed. Python and JS/TS use
heuristics; unsupported structures fall back to overlapping line chunks.

## Stack

| Area | Technologies |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1.1, Spring Security, JPA, Flyway, JJWT |
| Data | PostgreSQL 17, pgvector; vector dimensions fixed at 1536 |
| Frontend | React 19, Vite, Axios, React Router; Node 26 for build/CI |
| AI | OpenAI-compatible embedding and chat completion providers; explicit RAG services |
| Infrastructure | Docker Compose, Nginx, GitHub Actions |

## Quick start with Docker

Clone your published repository URL, then enter its directory:

```sh
git clone <your-devpilot-repository-url> devpilot
cd devpilot
cp .env.example .env
openssl rand -base64 48
```

Set `JWT_SECRET` in `.env` to the newly generated value. The example `change-me` is
intentionally too short for JWT signing. Keep `.env` private; it is ignored by Git
and excluded from Docker build contexts. No GitHub/OpenAI key is needed to start.

```sh
docker compose up --build
```

- Frontend: http://localhost:5174
- API health: http://localhost:8083/api/health
- Same-origin API health: http://localhost:5174/api/health
- PostgreSQL: localhost:5434; local development DB/user/password: `devpilot`

All host ports bind to loopback. Stop existing **DevPilot** manual processes on
5174/8083 before starting Docker, or configure alternative host ports in `.env`.
If changing the frontend port, also update `FRONTEND_BASE_URL`,
`GITHUB_REDIRECT_URI`, `CORS_ALLOWED_ORIGINS` and the GitHub OAuth app callback.
Do not stop unrelated applications to free a port.

Postgres readiness gates the backend; backend health gates Nginx. Flyway applies
migrations on startup and Hibernate validates the schema. The named volume
`devpilot_data` persists data across container replacement. Changing Postgres
credentials in `.env` does not change credentials in an initialized volume: use
an explicit database credential rotation procedure instead. Back up before upgrades.

```sh
docker compose ps
docker compose logs --tail=100 backend
docker compose stop
```

The backend image builds with Java 21/Maven wrapper, then runs as UID 10001 using a
stripped Java runtime that retains `jdk.compiler` for AST parsing. Frontend assets
are built with `npm ci` and served by Nginx. Nginx proxies `/api/` without changing
the path, allows 180 seconds for AI responses and provides SPA history fallback.
It disables access logs to avoid recording OAuth callback query strings.

## Environment variables

Copy `.env.example` for the complete list. Compose passes variables explicitly to
the backend; it does not bake secrets into either image. Spring Boot also accepts
these variables directly in the process environment. Vite variables are public,
build-time configuration and must never contain secrets.

| Variable | Requirement / behavior |
| --- | --- |
| `JWT_SECRET` | Required for Docker/deployment; at least 32 random bytes. Manual local dev has a development-only fallback. |
| `SPRING_DATASOURCE_URL` | Docker defaults to `jdbc:postgresql://postgres:5432/devpilot`; manual dev uses localhost:5434. |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Local defaults `devpilot`; use your own deployment credentials. |
| `POSTGRES_PORT`, `BACKEND_PORT`, `FRONTEND_PORT` | Compose host ports: 5434, 8083, 5174. |
| `SERVER_PORT` | Manual backend default 8083; Compose explicitly uses internal 8080. |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | Required only for GitHub connection; missing configuration produces an API error. |
| `GITHUB_TOKEN_ENCRYPTION_KEY` | Required for GitHub tokens; base64 of exactly 32 random bytes (`openssl rand -base64 32`). Preserve/back up independently of JWT secret. |
| `GITHUB_REDIRECT_URI` | Docker: `http://localhost:5174/api/github/callback`; manual: `http://localhost:8083/api/github/callback`. Must match the OAuth application. |
| `FRONTEND_BASE_URL` | Trusted frontend origin for OAuth success/failure redirects; default `http://localhost:5174`. |
| `CORS_ALLOWED_ORIGINS` | Explicit comma-separated origin allowlist for manual/cross-origin use. No wildcard credentials. |
| `OPENAI_API_KEY` | Required for indexing embeddings, semantic search and Ask; optional at startup. |
| `EMBEDDING_PROVIDER`, `EMBEDDING_BASE_URL`, `EMBEDDING_MODEL` | Defaults: `openai`, `https://api.openai.com/v1`, `text-embedding-3-small`. |
| `EMBEDDING_DIMENSIONS` | Must remain 1536 for the current schema; changing model requires compatible dimensions and reindexing. |
| `EMBEDDING_BATCH_SIZE`, `EMBEDDING_MAX_RETRIES`, `EMBEDDING_RETRY_DELAY_MILLIS` | Defaults: 50, 3, 500; bounded batching/retries. |
| `CHAT_PROVIDER`, `CHAT_BASE_URL`, `CHAT_MODEL` | Defaults: `openai`, `https://api.openai.com/v1`, `gpt-5-mini`; separate chat abstraction. |
| `CHAT_TIMEOUT_SECONDS` | Default 60. |
| `RAG_TOP_K`, `RAG_MAX_CONTEXT_CHARS`, `RAG_MAX_QUESTION_CHARS` | Defaults: 8, 30000, 4000. Compose builds frontend question limit to match. |
| `INDEXING_*` | File/total byte/chunk/line/job limits and overlap; every existing knob is listed in `.env.example`. |
| `VITE_API_BASE_URL` | Manual: `http://localhost:8083`. Docker sets `/`; API methods already include `/api`, so `/api` as the base would duplicate it. |
| `VITE_AI_MAX_QUESTION_CHARS` | Manual frontend question bound, default 4000; align with backend and rebuild after changes. |

### GitHub OAuth

Register a GitHub OAuth application using the callback for the selected mode.
Docker sends connect and callback requests through the same Nginx origin, preserving
the HttpOnly, SameSite=Lax browser-bound state cookie. The backend validates state
and PKCE, encrypts the token, and redirects browser callbacks to the configured
frontend `/github?connected=true` or `/github?error=oauth_failed`. JSON clients keep
the API response contract. Credentials and callback codes never belong in frontend
configuration. Real OAuth requires a configured application and user authorization.

## Manual development

Use Java 21 and Node 26. First prepare root `.env` as above so Compose can resolve
its configuration, then start only PostgreSQL:

```sh
docker compose up -d postgres
./mvnw spring-boot:run
```

The API runs on http://localhost:8083. Spring does **not** automatically load root
`.env`; export needed variables in the backend shell. Do not export the Docker DB
hostname `postgres` for a host process. Local property defaults use localhost:5434.
Set the manual GitHub callback to http://localhost:8083/api/github/callback.
In another terminal:

```sh
cd frontend
cp .env.example .env
npm ci
npm run dev
```

Open http://localhost:5174. See [frontend/README.md](frontend/README.md) for routes,
auth session behavior, cancellation, source rendering and frontend configuration.

## API overview

Protected requests require `Authorization: Bearer <access-token>`.

| Feature | Main endpoints |
| --- | --- |
| Health | `GET /api/health` |
| Auth | `POST /api/auth/register`, `/login`, `/refresh`, `/logout`; `GET /api/users/me` |
| GitHub | `GET /api/github/connect`, `/callback`, `/status`, `/repositories`; `POST /api/github/disconnect` |
| Repositories | `GET/POST /api/repositories`; `GET/DELETE /api/repositories/{id}` |
| Indexing | `POST /api/repositories/{id}/index`; `GET /api/repositories/{id}/index-status` |
| Search | `POST /api/repositories/{id}/search` with `{"query":"JWT validation","limit":8}` |
| Ask | `POST /api/repositories/{id}/ask` with `{"question":"How does JWT authentication work?"}` |

Indexing returns 202; status polling reports the latest attempt separately from the
published snapshot. Search returns real code chunks and similarity (not confidence).
Ask returns answer, only cited source metadata, configured model and `grounded`.
No Swagger UI is included.

## RAG grounding

```text
Question -> query embedding -> pgvector cosine search -> ranked whole chunks
 -> bounded source context -> LLM structured completion -> source ID validation
 -> answer with [1] citations and actual path/startLine/endLine metadata
```

RAG reuses the semantic retrieval service. Context separates repository content from
instructions and treats source text as untrusted evidence, including prompt injection
attempts. The provider must use only that evidence and abstain when insufficient.
Every returned statement must cite valid retrieved source IDs. Invalid output is
rejected; only sources cited by the answer are returned. Context is capped and
oversized chunks are skipped rather than silently clipped. `grounded=true` means
citation validation succeeded; it is **not proof of correctness** or a guarantee
against prompt injection. Answers require human review.

## Security and deployment boundaries

- BCrypt password hashing; JWT access tokens; rotating opaque refresh tokens stored
  only as hashes in the database.
- OAuth state, PKCE and browser binding; AES-256-GCM encryption for stored GitHub tokens.
- Ownership checks on repository/index/search/Ask operations; safe API error messages.
- Secret-file/path ingestion filtering and bounded input sizes. This is not a complete
  content secret scanner: inspect repositories before sending code to external providers.
- Repository chunks are sent to the configured embedding/chat services. Choose a
  provider and data policy appropriate for private source code.
- Frontend renders source/answer text without raw HTML. Access tokens remain in memory;
  refresh tokens use tab-scoped sessionStorage, which is JavaScript-accessible and
  therefore vulnerable to XSS. OAuth state alone uses an HttpOnly cookie.
- Health is public; other non-auth endpoints require authentication except OAuth callback.

The Compose defaults are for a local single-host installation, not an internet-facing
security policy. For public deployment configure HTTPS, trusted origins/callbacks,
strong database/JWT secrets, encrypted backups and operational monitoring. Set an
HTTPS GitHub callback so the state cookie is Secure. Do not expose the database
publicly. Authentication throttling, managed secret rotation and TLS termination are
not provided here. Do not dump resolved Compose environment configuration into logs.

## Tests and CI

Backend integration tests need a disposable PostgreSQL database with pgvector and
migration privileges. Tests create and clean their own fixture users/repositories;
never point a test suite at a production database.

```sh
./mvnw test
cd frontend
npm ci
npm test
npm run lint
npm run build
```

[GitHub Actions CI](.github/workflows/ci.yml) runs on push and pull requests with
separate Java 21 and Node 26 jobs. The backend uses a healthy pgvector/pgvector:pg17
service. External GitHub/OpenAI traffic is replaced by test doubles/deterministic
providers; CI needs no provider credentials. Frontend tests cover auth races,
repository UX and AI workspace behavior. Docker builds skip tests because CI runs
them separately; use both checks before release.

## Version and release

Backend and frontend are version **1.0.0**. Existing Flyway V1–V9 migrations are
immutable; schema changes require a new migration. No release/tag is created by
this setup. Before publication, run CI on GitHub, configure and test real OAuth/AI
flows in your environment, review dependencies and deployment settings, and choose
a license. This repository does not currently grant an open-source license.
