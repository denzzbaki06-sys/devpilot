# Intelligence implementation and limits

[Back to product overview](../README.md)

## Pull request ingestion

Connected repositories now expose read-only PR ingestion, independent of indexing
status. PR mutation is not implemented; M10B adds the read-only AI review described below.

- `GET /api/repositories/{id}/pull-requests?page=1&size=20`: open PRs; 1-based
  pages, size 1–100, page 1–1000, `hasNext` from GitHub Link metadata. Counts absent
  from the upstream list are null, not zero; details contain the actual counts.
- `GET /api/repositories/{id}/pull-requests/{number}`: detail, base/head SHA,
  nullable mergeability, changed files, patch availability, normalized hunks/lines
  and context statistics. Responses are `no-store` and JWT protected.
- UI: `/repositories/:id/pull-requests` and `/repositories/:id/pull-requests/:number`.
  Repository tabs include Pull Requests without a READY requirement. Expand a file
  to inspect its diff; Analyze Pull Request is available for READY repositories.

The backend checks ownership before reading the existing encrypted GitHub token,
uses the existing user lock to serialize disconnect, and verifies the current GitHub
repository ID to reject a recycled repository name. Calls use the existing `repo`
OAuth scope. GitHub 401/rate-limit errors use the shared safe mapping; upstream
URLs in pagination headers are never followed with credentials.

Changed files are fetched across all pages. Detail is read again after the files;
changed base/head SHA, update timestamp or file count returns 409 to avoid mixing
snapshots. On-demand context is sufficient, so no PR persistence or migration was
added. Applied V1–V9 remain unchanged.

The internal `PullRequestContextBuilder` is separate from API DTOs. Context lines
advance old/new line numbers, deletions only old, additions only new. No-newline
markers do not advance counters. Rename paths are preserved. Missing/blank patches
return `patchAvailable=false` and `UNAVAILABLE`; malformed/partial patches retain
raw metadata/text but return `MALFORMED` and no line mappings. Parsed addition and
deletion totals must also match file metadata, catching patches truncated at a hunk
boundary. An unavailable patch alone is not proof that the file is binary.

Statistics expose total changed files, total patch characters, available/unavailable
patch counts and malformed count. Defensive limits reject the whole request rather
than truncate: 3000 changed files (GitHub's files endpoint cap), 8 MB per upstream
response, 10 million total patch characters and 200,000 patch lines before parsing.
Files pagination has a 120-second deadline checked between requests; an in-flight
request retains its 15-second timeout. Oversize is 413, changed snapshot is 409,
upstream timeout/unavailability uses safe structured errors. GitHub retrieval is not
an atomic snapshot API; the consistency checks detect observed changes, not every
possible concurrent change.

PR titles, descriptions and patches are untrusted strings. The UI renders text
without HTML execution and only allows HTTPS github.com external links with
`noopener noreferrer`. AI review is explicitly initiated by the user; no fabricated reviews are displayed. GitHub API
contract reference: [GitHub REST pull requests](https://docs.github.com/en/rest/pulls/pulls).


## AI pull request review

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

## Deterministic architecture analysis

`GET /api/repositories/{id}/architecture` requires JWT ownership and a READY
repository. It returns DTOs with components, relationships, entry points, external
imports, statistics, warnings and `indexCommitSha`. No provider key is needed: this
endpoint does not call GitHub, semantic search, embeddings or an LLM.

The store reads only the published `repository_files` / `code_chunks` snapshot in
a repeatable-read transaction. Overlapping chunk lines must agree. Missing blank
partitions are restored only when the whole normalized source hash matches the
indexed file (including optional final newline). Incomplete or inconsistent files
are omitted with a warning, rather than parsed as invented source. Ingestion file
filters and conservative literal-secret filtering apply again. No staged files,
vectors, runtime credentials or raw source bodies appear in the response.

Java parsing shares `JavaSourceParser` with the existing chunker and uses JDK21
parse-only APIs: no processors, compilation, classpath resolution or code execution.
It extracts named member classes/interfaces/records, source ranges, known Spring
stereotypes, constructors, fields, methods, bean names, literal controller routes,
entity/table names, and Spring Boot or public static main entry points. HTTP-client
fields identify wrappers without inventing GitHub/OpenAI remote targets.

Supported edges are `DEPENDS_ON` (single Spring constructor or explicitly autowired
constructor), `INJECTS` (autowired field), `EXTENDS`, `IMPLEMENTS` and
`MANAGES_ENTITY` (known Spring Data generic base). Internal targets require a unique
explicit-import or same-package qualified name. Unresolved/ambiguous targets are
counted, never synthesized. No CALLS, READS_FROM or WRITES_TO claims are made without
semantic symbol resolution. Application wildcard imports, inherited injection,
Lombok-generated constructors, aliases and meta-annotations are not resolved.
Dynamic/nonliteral route values are omitted. This is conservative static analysis,
not a complete compiler or an exact runtime dependency graph.

JS/TS/Python reuse the lightweight chunker symbol extraction with a lexical mask.
Top-level named classes/functions and module nodes are supported. JS/TS explicit
relative static imports resolve only when one indexed file matches; package imports
are listed as external import evidence, not graph nodes or remote services. Python
absolute imports resolve from the indexed root, relative imports from the file's
package directory. Alternate source roots, re-exports, dynamic imports, React and
Python framework inference are omitted. Unresolved modules remain unresolved.

IDs are SHA256-derived from language, path and qualified declaration key (script
symbol keys also include declaration line to distinguish repeated names). IDs and
ordering are deterministic for a snapshot; they do not depend on database row IDs.
Edges deduplicate source/target/type with representative source evidence; self edges
are removed. Component/edge limits use deterministic ordering and visible warnings.

Defaults: `ARCHITECTURE_MAX_FILES=300`, `ARCHITECTURE_MAX_SOURCE_CHARS=3000000`,
`ARCHITECTURE_MAX_COMPONENTS=1000`, `ARCHITECTURE_MAX_RELATIONSHIPS=3000`.
File selection conservatively budgets indexed bytes before fetching text. Bulk chunk
fetch is capped at20000 rows and twice the source-character budget for overlaps;
excess returns a controlled413. DB query count is constant rather than per file.
Results are request-time, uncached and not persisted. The index generation/commit and
ownership/READY state are rechecked after parsing; observed changes return409.
V1–V9 remain unchanged and no migration was added.

Protected UI: `/repositories/:id/architecture`. Repository tabs include Architecture.
The page shows real summary counts, type groups, source locations, expandable
component details, routes/table metadata, incoming/outgoing relationships, entry
points and scope warnings. Empty, non-READY, removed repository and API-error states
contain no example results. Requests abort and stale responses are ignored. M11A
adds no graph library or AI explanation. Source navigation is path/line metadata;
M11B adds the interactive graph described below; a full source viewer remains future work.


## Interactive Architecture Map

Architecture opens in **Graph** mode; **Components** preserves the M11A structured
view and remains available if graph loading or layout fails. The graph is a pure
presentation of the existing architecture response: original component IDs,
directed relationships, labels and source evidence. No analyzer change, new
relationship inference, AI call, database migration or persisted layout is involved.

Search name, qualified name, path, symbol or type locally, then select a result to
center it and inspect source metadata. Selected nodes highlight direct incoming
(dashed amber) and outgoing (solid cyan) relationships. Node/relationship filters
hide incident edges consistently; Reset view clears search, filters and selection
and fits the graph. Pan, zoom, Fit View and a conditional minimap support larger maps.
Snapshot SHA, analysis warnings and actual type labels remain visible. Unknown
future enum values retain their backend labels. Source buttons copy exact path/line
references; they do not fabricate repository URLs or pretend a source viewer exists.

React Flow 12.11.6 supplies interaction/accessibility and Dagre 3.1.1 supplies stable
hierarchical coordinates. The lazy graph bundle and layout Web Worker keep the graph
out of the initial route bundle and layout off the main thread. A 15-second layout
budget terminates stuck work and offers Components fallback. Graphs near the backend
budget can be visually dense at fit-to-screen scale; search and zoom are the primary
way to inspect individual components. Layout is recomputed for a new response or
remounted Graph view, not for search/filter/selection changes. See frontend README
for library rationale, testing boundaries and measured sizes.

## AI Architecture Q&A

`POST /api/repositories/{repositoryId}/architecture/ask` is a JWT-protected,
owner-only, READY-only single-turn architecture question. Request fields are
`question` (required, nonblank, <=4000 characters by default), optional
`selectedComponentId`, and optional `indexCommitSha` for checking the displayed
snapshot. Other fields, including model/provider/system prompt, are rejected.
The selected ID is resolved inside that repository's deterministic architecture
snapshot; client metadata is never trusted.

The response contains `repositoryId`, plain-text `answer`, `sources`, `components`,
`relationships`, `indexCommitSha`, `warnings` and `grounded`. Source objects use
`id: S1`, actual chunk ID/path/lines/symbol/language; component objects use
`ref: A1` plus actual ID/name/type; relationship objects use `ref: E1` plus actual
source/target/type/evidence. Only references used by validated statements are
returned. Raw provider JSON, vectors and source content are not returned.

The orchestration reuses M11A analysis once, M5 semantic retrieval, M6 whole-chunk
context budgeting, and the existing structured `LlmProvider` transport/retries.
Selected components use bounded bidirectional BFS with visited IDs (depth 1 by
default, configurable hard maximum 2). Repository-wide selection matches real
name/qualified name/symbol/path/type/route text, semantic chunk locations and
entry points. No architecture embeddings or aggressive natural-language path
parser is added. Relationship direction/type is preserved; no inferred CALLS edge
or graph path is generated. Model prose remains an interpretation of evidence,
not a formally verified call graph or a proof of runtime behavior.

Source candidates are loaded in one owner-scoped bulk query (<=120 chunks),
prioritizing selected/component-neighborhood paths. Up to three overlapping chunks
per component are considered. Half the prompt chunk slots are reserved for ranked
semantic results; existing similarity values are preserved. Chunk identity and
canonical path/range deduplicate candidates. Full chunks only; references retain
original line spans. The builder enforces a serialized JSON context budget,
including question and metadata; fixed system policy/schema are additional bounded
provider overhead. Oversized evidence is omitted, not silently relabeled.

Defaults (`ARCHITECTURE_ASK_` environment prefix):

| Suffix | Default | Hard maximum |
| --- | ---: | ---: |
| MAX_QUESTION_CHARS | 4000 | 4000 |
| MAX_COMPONENTS | 24 | 60 |
| MAX_RELATIONSHIPS | 40 | 100 |
| DEPTH | 1 | MAX_DEPTH |
| MAX_DEPTH | 2 | 2 |
| TOP_K | 8 | 20 |
| MAX_CHUNKS | 12 | 30 |
| MAX_CONTEXT_CHARS | 30000 | 80000 |

The published generation and commit SHA are checked before/after architecture
analysis, after retrieval/context building, and after the model call. Observed
reindex/ownership/READY changes fail with 409/404 instead of returning mixed results.
These are optimistic checks, not a distributed lock against changes after the final
check. Unknown A/E/S references, extra model metadata, malformed/uncited statements
or literal credential patterns fail closed. Each statement needs an actual source
reference and a component or relationship reference. `grounded` means references
were validated; it does not certify the model's reasoning.

Question, names, comments, code and metadata are untrusted user-message data,
separated from a fixed system policy. Existing sensitive-path and literal-secret
filters are reused; runtime environment/configuration is not part of prompt input.
Pattern filtering is defense in depth, not a complete secret scanner or a guarantee
against prompt injection. Full prompts and credentials are not logged. Missing chat
configuration returns controlled 503; existing provider 429/502/504 handling and
bounded retries are reused. Insufficient evidence returns a fixed abstention with
no invented citations. An empty architecture returns controlled 409 without an AI call.

Architecture UI adds a compact question panel, selected-node context, Clear context,
Explain Component and Explain Connections. A citations select/center actual nodes;
E citations inspect existing edges; S citations open exact source-location copy
controls. Show in graph highlights existing referenced nodes and reveals their
filtered types. HTML in answers is rendered as text. Repository/snapshot/context
changes or unmount abort requests and discard late results. Browser cancellation
cannot guarantee cancellation of an already-running upstream model request.

No conversation memory, persistence, migration, agent loop, patch, auto-fix or PR
creation is included. V1–V9 remain unchanged. No new package dependency was needed.
