# DevPilot frontend

React + Vite, JavaScript. Verified with Node 26.7.0.

```sh
cd frontend
npm ci
cp .env.example .env
npm run dev
```

Open http://localhost:5174. Port 5173 is occupied by another local project. For manual development, the API URL is configured by `VITE_API_BASE_URL` in `.env` (example: http://localhost:8083). Vite variables are public build-time configuration, never secrets.

```sh
npm run build
npm run lint
npm test
```

## Structure

- `api/`: Axios clients, token state, single-flight refresh
- `context/`, `hooks/`: auth lifecycle and `useAuth`
- `routes/`: public/protected guards
- `layouts/`, `components/`: responsive shell and shared components
- `pages/`: login/register and workspace foundation pages
- `styles/`: global design tokens and responsive styles
- `utils/`: safe error normalization
- `tests/`: Node test runner tests for authentication races and storage

Public routes: `/`, `/login`, `/register`. `/` redirects to login or the authenticated dashboard.
Protected routes: `/dashboard`, `/repositories`, `/github`, `/settings`.

Access tokens live in memory. Rotating refresh tokens use **sessionStorage**, not localStorage; this restores a session on reload within the same tab. If storage is unavailable the session is memory-only. Session storage is JavaScript-accessible and therefore vulnerable to XSS; it is not equivalent to an HttpOnly cookie. This choice follows the existing backend body-token contract without changing authentication architecture. No tokens are logged or rendered.

A shared refresh promise serializes concurrent 401 recovery. Requests retry once; late 401 responses reuse an already refreshed access token. Refresh failure clears the session. Logout waits for an in-flight rotation and revokes the latest refresh token, then clears local state. A network failure during logout still signs out locally, but server-side revocation cannot be guaranteed while offline.

The backend accepts an explicit `CORS_ALLOWED_ORIGINS` comma-separated allowlist. Development defaults are `http://localhost:5173,http://localhost:5174`. Configure deployment origins explicitly. GitHub routes additionally allow the browser-bound OAuth state cookie, as described below; other routes continue to use Bearer-only CORS.

Dashboard counts come from real repository data. GitHub status comes from the real status endpoint. Missing/loading/failed states do not fabricate counts. GitHub connection and repository import/indexing controls are available in M7B. Ask and semantic-search interfaces are available in M7C.

Typography uses Google Fonts with local system fallbacks. The multi-stage Dockerfile builds static assets and serves them through Nginx with SPA fallback and a same-origin `/api/` proxy. See the [root README](../README.md) for full stack setup. Docker sets `VITE_API_BASE_URL=/` because API modules already include `/api` in their paths; do not use `/api` as the base URL.

## M7B — GitHub and repositories

`/github` now supports connect/disconnect, remote repository search and visibility filters, and adding repositories to DevPilot. `/repositories` shows real connected repositories. `/repositories/:id` provides metadata, indexing/reindex, published index counts, the latest attempt summary, and confirmed removal. `/repositories/:id/ask` and `/repositories/:id/search` now provide the M7C AI interfaces described below.

OAuth connect reads `authorizeUrl` from the backend and navigates in the same tab. The connect/disconnect Axios calls enable `withCredentials` for the HttpOnly OAuth state cookie. Backend CORS allows credentials **only for `/api/github/**`** and only for the configured origin allowlist. Other API CORS settings remain unchanged.

Set `FRONTEND_BASE_URL` to a trusted frontend origin (default `http://localhost:5174`). Browser callbacks requesting `text/html` redirect to `/github?connected=true` or `/github?error=oauth_failed`; API clients requesting JSON retain the original status/error contract. Set the GitHub OAuth application's callback to the configured `GITHUB_REDIRECT_URI`, locally `http://localhost:8083/api/github/callback`. Existing state validation, browser binding and PKCE remain intact.

Index status is polled every 2.5 seconds **after the preceding request finishes**, only while INDEXING. Unmount aborts requests and clears timers; terminal status or error stops polling. A status error offers manual retry. Counts label the published snapshot separately from the latest attempt; no fabricated progress percentage is shown.

`npm test` runs the existing Node auth tests followed by Vitest/Testing Library component and API tests in jsdom. Test-only fixtures never enter the application or database. Real GitHub OAuth and live ingestion require actual GitHub OAuth settings, token encryption configuration, and embedding provider credentials; missing configuration is shown as an actionable error.

## M7C — AI Workspace

M7C replaces the Ask/Search placeholders with live API-backed interfaces. Global `/ask` and `/search` routes select only READY repositories; a single READY repository opens directly. `?choose=true` keeps the picker open for “Change repository”. Repository detail tabs and dashboard/sidebar links lead to these flows.

Each Ask request is standalone. The page stores only the current result in component memory; it does not persist a conversation. `/ask` sends `{question}` and uses the backend default topK. `/search` sends `{query,limit}` on explicit submission, never on typing. Limits match backend defaults: question 4000, query 2000, search limit choices 5/8/10/20. If `RAG_MAX_QUESTION_CHARS` is customized lower on the backend, set `VITE_AI_MAX_QUESTION_CHARS` to the same value and rebuild the frontend.

The repository is checked on entry and again immediately before each AI request. CONNECTED/INDEXING/FAILED show a status-specific route back to indexing. AI calls get a 180-second timeout to accommodate embedding plus chat work. Navigating away, changing repository, or pressing Cancel aborts the browser request and invalidates its result sequence. Cancellation does not guarantee cancellation of already-running server/provider work. Older results remain explicitly labeled when a newer request is pending or fails.

The M6 answer contract is plain text with server-appended `[1]`, `[2]` citations into the returned `sources` array. Only in-range numeric references outside fenced code are interactive; clicking focuses the corresponding source card. Unknown references remain text. No raw HTML, HTML injection, or external link execution is used. Ask sources contain metadata only; Search results contain actual chunk content rendered in a scrollable `<pre><code>`. Similarity is the actual 0–1 value displayed as a percentage, not an AI confidence claim.

`grounded=false` is a neutral insufficient-evidence state with exactly the sources supplied by the API (normally none). No demo response or fallback source exists in application code. Provider configuration/rate-limit/timeout and repository-access errors have safe local messages. Copy answer/code uses the Clipboard API and copies only the corresponding response content.
