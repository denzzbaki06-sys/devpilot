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

## M10A — Pull Requests

Protected repository PR list/detail routes are wired to the read-only backend API.
The list fetches one page at a time and leaves absent GitHub counts undisplayed.
Detail preserves branch/SHA/rename information and expands normalized diffs with
old/new line columns plus addition/deletion symbols. Missing patches show a neutral
unavailable message; malformed patches show raw text without line citations.
PR body and code are escaped React text. External links allow HTTPS github.com
only. Request cancellation and keyed route state prevent stale repository results.
PR browsing has no READY gate. M10B review requires READY; PR mutation and demo data remain absent.


## M10B — Read-only AI pull request review

`POST /api/repositories/{id}/pull-requests/{number}/review` requires JWT, ownership
and a READY index. Optional `focus` accepts BUG, SECURITY, MAINTAINABILITY, TESTING;
the default includes all four. Optional `expectedBaseSha`/`expectedHeadSha` protect
the displayed snapshot. Arbitrary prompt fields are rejected.

M10A supplies parsed diff hunks. Each whole hunk is a bounded review unit; M4 file
filters omit generated, vendor, binary, sensitive and unavailable/malformed patches.
M5 semantic retrieval supplies up to three related chunks per unit. M6's existing
chat provider produces strict structured output. Backend-generated D references map
only to changed diff lines (RIGHT additions or LEFT deletions); R references map to
retrieved chunk metadata. Unknown refs, model paths/lines and invalid output fail
with a controlled provider error. No partial result is returned after batch failure.

Defaults, configurable through `.env.example`: 12 files, 40,000 diff characters,
24 distinct retrieved chunks, 80,000 total serialized context characters, 16,000 per
batch, 8 batches, 40 final findings and a 300-second deadline checked between calls.
An in-flight upstream request can outlast that deadline; browser/Nginx timeouts are
900 seconds. Chat retries at most twice for 429, transient 5xx and transport timeout,
with 250ms incremental backoff; 400/401 are not retried. Cancellation invalidates the
browser result but cannot guarantee cancellation of already-running provider work.

Response includes base/head/index SHA and BASE/HEAD/OTHER/UNKNOWN relation. A differing
or unknown index is explicitly background context. Index generation and PR SHAs are
rechecked; observed concurrent changes return 409. These guards are not a distributed
atomic snapshot. Skipped/partially reviewed files and missing evidence are reported.
Findings are deduplicated deterministically; severity counts, risk and summary come
from validated findings. Empty findings use NONE and make no safety guarantee.
`grounded` means references were validated, not that the model's reasoning is proven.

PR text and code remain untrusted data, separated from system instructions. No runtime
credentials enter review input; known source secret patterns are filtered, but pattern
matching is not a complete secret scanner or a guarantee against prompt injection.
Missing AI configuration allows startup and returns a safe error on review invocation.
No live model reliability claim follows from deterministic fake-provider tests.

The READY-only Analyze button shows loading/cancel and prevents duplicate requests.
The panel shows severity text badges, recommendations, actual source evidence,
skipped-file/snapshot warnings and clickable locations that open/focus the correct
renamed-file diff line. Changing PR or snapshot invalidates stale results. No score,
review history, comments, GitHub review submission, patches or agent loop is added.
V1–V9 remain immutable; no migration is needed.

## M11A — Architecture workspace

The protected `/repositories/:id/architecture` page calls the deterministic backend
architecture endpoint only for READY repositories. It displays source-backed counts,
component groups, native expandable details, incoming/outgoing edges, literal routes,
entity metadata and entry points. Source paths/lines remain text; no unsupported
source viewer or fabricated graph is presented. Scope limits and unavailable sources
are visible. Navigation/unmount cancels requests and stale results are discarded.
No graph library, AI provider, score or demo data was added. See the root README for
supported language constructs, conservative resolution and environment limits.


## M11B — Interactive graph implementation

`ArchitecturePage` owns Graph/Components modes and the lazy-load error boundary.
`components/architecture/architectureGraphAdapter.js` owns DTO transformation,
exact-edge deduplication, filtering, search and directional selection highlighting.
IDs come directly from components; since M11A has no edge ID, a stable tuple of
source/target/type/evidence identifies each edge. Distinct evidence is retained.
Missing endpoints are omitted with a visible warning, never replaced by fake nodes.
`architectureLayout.js` runs Dagre per connected island with stable ordering and
shelf-packs disconnected islands; only coordinates change. The worker is terminated
on completion, timeout or unmount. `ArchitectureGraph` manages transient view state;
`ArchitectureDetailPanel` renders actual component or relationship evidence.

### Library decision

Added exact production dependencies `@xyflow/react@12.11.6` and
`@dagrejs/dagre@3.1.1`; no additional development dependency. React Flow's React >=17
peer range includes the existing React 19.2 app; Vite 7 production build and real
browser tests passed. Both are maintained packages (registry releases checked before
installation: React Flow September 2026, Dagre August 2026). React Flow provides DOM
nodes, SVG directed edges, pan/zoom, keyboard selection, controls and minimap without
a custom canvas/physics engine. Dagre is a small deterministic layout dependency
with an [official React Flow integration example](https://reactflow.dev/examples/layout/dagre).

The graph is lazy loaded: production graph JS approximately 196.31 kB / 63.94 kB gzip,
CSS 23.22 kB / 4.44 kB gzip, and worker 49.50 kB uncompressed. Main JS is 402.73 kB /
129.16 kB gzip (M11A: 400.01 / 128.08). No Vite chunk-size warning. The graph cost is
paid when Graph is opened; Components does not depend on successful graph rendering.

Focusable graph elements support Enter/Space selection and Escape closes details;
search results and Components provide a text-based alternative. Details stack below
the canvas at <=1150px; mobile hides the minimap. Colors are accompanied by type
labels, directional text, arrows and dashed incoming edges. See React Flow's
[accessibility documentation](https://reactflow.dev/learn/advanced-use/accessibility).

### Validation boundaries

Adapter/layout tests use actual Dagre. Component tests use a lightweight React Flow
facade to verify application behavior without relying on jsdom geometry; real browser
smoke covers actual React Flow/worker rendering, selection, keyboard, search, filters,
reset, fallback modes and responsive layout. This distinction follows the library's
[testing guidance](https://reactflow.dev/learn/advanced-use/testing).
Test fixtures live only under `tests/fixtures`; production code never imports them.
Small (10/9), medium (100/99), and budget-size (1000/2922 nodes/edges) fixtures were
checked. Representative local Dagre measurements were 5ms, 15ms and 465ms, respectively;
these are machine-specific layout timings, not a guaranteed browser frame rate.
No live indexed repository was available, so positive graph smoke used an isolated
fixture server and did not insert production repository data.

## M11C — Architecture questions

`ArchitectureViews` keeps AI context/highlight state separate from transient graph
selection. `ArchitectureAiPanel` submits only question, selected backend ID and
snapshot SHA through `api/architecture.js`. Repository-wide questions omit the ID.
The panel remains available in Graph and Components modes. Quick actions use fixed
questions; names, paths and graph data are resolved by the backend, not injected by
the browser. Empty architecture disables the panel; provider configuration, timeout,
index conflict and other failures have safe messages.

The existing plain-text `AnswerText` renderer now accepts an optional validated
reference map for A/E/S citation buttons; its numeric repository-Q&A contract is
unchanged. No raw HTML/Markdown execution is introduced. Component/relationship
citation targets must exist in the current graph; citations navigate without
silently changing the active question context. Source citations use the existing
path/line copy foundation. Show in graph highlights actual backend IDs and focuses
the map; Clear highlight removes the answer overlay.

Requests carry AbortSignal, an immediate double-submit guard and a generation token.
Changing repository, snapshot or selected context clears stale answers and cancels
in-flight work. Explicit Cancel analysis permits another question. No conversation
history or local persistence is added. Textarea/quick actions/citations are labeled;
Ctrl/Command+Enter submits. Backend validation remains authoritative.

Unit tests cover the panel/API, citation interactions, safe rendering, cancellation,
unknown nodes, graph selection/centering/highlighting and error states. Real-browser
positive UI smoke uses a clearly labeled isolated deterministic fixture; it is not a
live OpenAI reliability test. No fixture import is included in the production bundle.
