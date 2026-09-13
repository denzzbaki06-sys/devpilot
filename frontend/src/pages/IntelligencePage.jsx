import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  Sparkles,
  ArrowUpRight,
  Search,
  ArrowRight,
  ShieldCheck,
  FileCode2,
  CornerDownLeft,
} from "lucide-react";
import { getRepository } from "../api/repositories";
import { askRepository, semanticSearch } from "../api/intelligence";
import {
  MAX_QUESTION_CHARS,
  MAX_QUERY_CHARS,
  intelligenceError,
  similarityLabel,
} from "../utils/intelligence";
import { PageHeading, LoadingPanel } from "../components/RepositoryUi";
import { ErrorMessage } from "../components/Feedback";
import {
  RepositoryContext,
  ReadyGuard,
} from "../components/intelligence/RepositoryContext";
import AnswerText from "../components/intelligence/AnswerText";
import SourceCard from "../components/intelligence/SourceCard";
import CopyButton from "../components/intelligence/CopyButton";
const examples = {
  ask: [
    "How is authentication structured?",
    "What happens when a repository is indexed?",
    "Where are API errors handled?",
    "Explain the main architecture of this codebase.",
  ],
  search: [
    "JWT validation",
    "repository indexing flow",
    "GitHub OAuth callback",
    "refresh token rotation",
  ],
};
export default function IntelligencePage({ kind }) {
  const { id } = useParams();
  return <IntelligenceWorkspace key={`${id}-${kind}`} id={id} kind={kind} />;
}
function IntelligenceWorkspace({ id, kind }) {
  const isAsk = kind === "ask",
    max = isAsk ? MAX_QUESTION_CHARS : MAX_QUERY_CHARS;
  const [repo, setRepo] = useState(null),
    [repoError, setRepoError] = useState(""),
    [loading, setLoading] = useState(true),
    [revision, setRevision] = useState(0);
  const [input, setInput] = useState(""),
    [limit, setLimit] = useState(8),
    [busy, setBusy] = useState(false),
    [pending, setPending] = useState(""),
    [result, setResult] = useState(null),
    [error, setError] = useState("");
  const activeRequest = useRef(null),
    requestNumber = useRef(0),
    composer = useRef(null),
    sourceRefs = useRef([]);
  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setLoading(true);
    setRepoError("");
    getRepository(id, controller.signal)
      .then((data) => {
        if (active) setRepo(data);
      })
      .catch((err) => {
        if (active) setRepoError(intelligenceError(err));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [id, revision]);
  useEffect(
    () => () => {
      requestNumber.current++;
      activeRequest.current?.abort();
    },
    [],
  );
  function cancel() {
    requestNumber.current++;
    activeRequest.current?.abort();
    activeRequest.current = null;
    setBusy(false);
    setPending("");
  }
  async function submit(event) {
    event.preventDefault();
    if (busy || repo?.status !== "READY") return;
    if (!input.trim() || input.length > max) {
      setError(
        `Enter a ${isAsk ? "question" : "query"} between 1 and ${max} characters.`,
      );
      return;
    }
    const query = input.trim(),
      controller = new AbortController(),
      ticket = ++requestNumber.current;
    activeRequest.current?.abort();
    activeRequest.current = controller;
    setBusy(true);
    setPending(query);
    setError("");
    try {
      const current = await getRepository(id, controller.signal);
      if (ticket !== requestNumber.current) return;
      setRepo(current);
      if (current.status !== "READY") {
        setResult(null);
        return;
      }
      const data = isAsk
        ? await askRepository(id, query, undefined, controller.signal)
        : await semanticSearch(id, query, limit, controller.signal);
      if (ticket !== requestNumber.current || controller.signal.aborted) return;
      if (
        String(data.repositoryId) !== String(id) ||
        (isAsk
          ? typeof data.answer !== "string" || !Array.isArray(data.sources)
          : !Array.isArray(data.results))
      )
        throw new Error("Invalid intelligence response");
      setResult(data);
    } catch (err) {
      if (ticket !== requestNumber.current || controller.signal.aborted) return;
      setError(intelligenceError(err));
      if (err.response?.status === 409 || err.response?.status === 404) {
        setResult(null);
        setRevision((v) => v + 1);
      }
    } finally {
      if (ticket === requestNumber.current) {
        setBusy(false);
        setPending("");
        activeRequest.current = null;
      }
    }
  }
  function citation(number) {
    const source = sourceRefs.current[number - 1];
    source?.scrollIntoView?.({ behavior: "auto", block: "nearest" });
    source?.focus();
  }
  function example(text) {
    setInput(text);
    setError("");
    composer.current?.focus();
  }
  if (loading) return <LoadingPanel label="Opening repository intelligence…" />;
  if (repoError)
    return (
      <>
        <ErrorMessage>{repoError}</ErrorMessage>
        <div className="guard-actions">
          <button
            className="secondary-button"
            onClick={() => setRevision((v) => v + 1)}
          >
            Try again
          </button>
          <Link className="text-action" to="/repositories">
            Go to repositories →
          </Link>
        </div>
      </>
    );
  if (!repo) return null;
  return (
    <div className={`intelligence-page ${kind}-workspace`}>
      <RepositoryContext repo={repo} kind={kind} />
      <PageHeading
        eyebrow={isAsk ? "UNDERSTANDING, WITH EVIDENCE" : "EXPLORE YOUR SOURCE"}
        title={isAsk ? "Ask DevPilot" : "Semantic Search"}
        description={
          isAsk
            ? "Ask questions grounded in your repository's actual source code."
            : "Find code by meaning, not just exact text."
        }
      />
      {repo.status !== "READY" ? (
        <ReadyGuard repo={repo} onRefresh={() => setRevision((v) => v + 1)} />
      ) : (
        <>
          <form
            className={`intelligence-composer ${isAsk ? "question-composer" : "search-composer"}`}
            onSubmit={submit}
            aria-label={isAsk ? "Ask repository" : "Search repository"}
          >
            <div className="composer-label">
              <label htmlFor="intelligence-input">
                {isAsk ? "Your question" : "Search query"}
              </label>
              <span className="mono muted">
                {isAsk
                  ? "ONE QUESTION · ONE CODEBASE"
                  : "MEANING OVER KEYWORDS"}
              </span>
            </div>
            {isAsk ? (
              <textarea
                id="intelligence-input"
                ref={composer}
                value={input}
                maxLength={max}
                placeholder="Ask about this repository..."
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (
                    e.key === "Enter" &&
                    !e.shiftKey &&
                    !e.nativeEvent.isComposing
                  ) {
                    e.preventDefault();
                    if (!busy) e.currentTarget.form.requestSubmit();
                  }
                }}
                aria-describedby="composer-help"
                rows={3}
              />
            ) : (
              <div className="search-query-row">
                <Search size={20} />
                <input
                  id="intelligence-input"
                  ref={composer}
                  value={input}
                  maxLength={max}
                  placeholder="Describe the code you’re looking for…"
                  onChange={(e) => setInput(e.target.value)}
                />
                <label className="limit-label">
                  Results
                  <select
                    aria-label="Result limit"
                    value={limit}
                    onChange={(e) => setLimit(Number(e.target.value))}
                  >
                    {[5, 8, 10, 20].map((n) => (
                      <option value={n} key={n}>
                        {n}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            )}
            <div className="composer-bottom">
              <span id="composer-help" className="composer-help">
                {isAsk ? (
                  <>
                    <CornerDownLeft size={12} /> Enter to ask · Shift+Enter for
                    a new line
                  </>
                ) : (
                  "Search runs only when you submit."
                )}
                <span>
                  {input.length.toLocaleString()} / {max.toLocaleString()}
                </span>
              </span>
              <div className="composer-actions">
                {busy && (
                  <button
                    type="button"
                    className="text-button"
                    onClick={cancel}
                  >
                    Cancel request
                  </button>
                )}
                <button
                  type="submit"
                  className="primary inline"
                  disabled={busy}
                >
                  {isAsk ? <Sparkles size={16} /> : <Search size={16} />}{" "}
                  {busy
                    ? isAsk
                      ? "Analyzing repository…"
                      : "Searching…"
                    : isAsk
                      ? "Ask DevPilot"
                      : "Search code"}
                  <ArrowUpRight size={15} />
                </button>
              </div>
            </div>
          </form>
          <ErrorMessage>{error}</ErrorMessage>
          {busy && (
            <div className="analysis-state" role="status">
              <span className="analysis-indicator" />
              <div>
                <strong>
                  {isAsk
                    ? "Analyzing repository..."
                    : "Searching repository..."}
                </strong>
                <p>{pending}</p>
              </div>
            </div>
          )}
          {!result && !busy && !error && (
            <section className="intelligence-empty">
              <div className="empty-intelligence-icon">
                {isAsk ? <Sparkles size={24} /> : <Search size={24} />}
              </div>
              <h2>
                {isAsk
                  ? "A better question. A clearer codebase."
                  : "What are you looking for?"}
              </h2>
              <p>
                {isAsk
                  ? "Follow the implementation, with references back to the source. Each question stands on its own."
                  : "Describe behavior, a concept, or a workflow. Results come from this repository’s indexed code."}
              </p>
              <span className="example-label">
                {isAsk
                  ? "EXAMPLE QUESTIONS — NOT CLAIMS ABOUT THIS REPOSITORY"
                  : "TRY A QUERY LIKE…"}
              </span>
              <div className="suggestion-grid">
                {examples[kind].map((text) => (
                  <button key={text} onClick={() => example(text)}>
                    <span>{text}</span>
                    <ArrowRight size={15} />
                  </button>
                ))}
              </div>
              {isAsk && (
                <div className="evidence-footer">
                  <FileCode2 size={14} /> Repository context <span>→</span>{" "}
                  Answer <span>→</span> Source references
                </div>
              )}
            </section>
          )}
          {result && (
            <section
              className={`intelligence-result ${busy ? "previous-result" : ""}`}
              aria-label={isAsk ? "Answer result" : "Search results"}
            >
              <div className="result-kicker">
                {busy || error
                  ? "PREVIOUS COMPLETED RESULT"
                  : "LATEST COMPLETED RESULT"}
                <span>
                  {isAsk
                    ? "SINGLE QUESTION · NO CONVERSATION HISTORY"
                    : `${result.results.length} RESULTS`}
                </span>
              </div>
              {isAsk ? (
                <>
                  <div className="asked-question">
                    <span>YOUR QUESTION</span>
                    <p>{result.question}</p>
                  </div>
                  <div className="answer-layout">
                    <article className="answer-panel">
                      <div className="answer-heading">
                        <div>
                          <Sparkles size={19} />
                          <h2>From your codebase</h2>
                        </div>
                        <CopyButton text={result.answer} label="Copy answer" />
                      </div>
                      <div
                        className={`grounded-label ${result.grounded ? "has-evidence" : "low-evidence"}`}
                      >
                        <ShieldCheck size={14} />
                        {result.grounded
                          ? "Grounded in repository sources"
                          : "Not enough repository evidence"}
                      </div>
                      <AnswerText
                        answer={result.answer}
                        sources={result.sources}
                        onCitation={citation}
                      />
                      <footer className="answer-footer">
                        <span>MODEL</span>
                        <code>{result.model || "Not provided"}</code>
                      </footer>
                    </article>
                    <aside
                      className="answer-sources"
                      aria-label="Repository sources"
                    >
                      <div className="sources-heading">
                        <h2>Source references</h2>
                        <span>{result.sources.length}</span>
                      </div>
                      {result.sources.length ? (
                        result.sources.map((source, index) => (
                          <SourceCard
                            key={`${source.chunkId}-${index}`}
                            source={source}
                            number={index + 1}
                            sourceRef={(node) => {
                              sourceRefs.current[index] = node;
                            }}
                          />
                        ))
                      ) : (
                        <p className="no-sources">
                          No source references were returned.
                        </p>
                      )}
                    </aside>
                  </div>
                </>
              ) : (
                <>
                  <div className="search-result-title">
                    <h2>Results for “{result.query}”</h2>
                    <span className="mono muted">RANKED BY SIMILARITY</span>
                  </div>
                  {result.results.length ? (
                    result.results.map((chunk, index) => (
                      <article
                        className="code-result-card"
                        key={`${chunk.chunkId}-${index}`}
                      >
                        <header>
                          <div className="code-result-identity">
                            <span className="result-rank">
                              {String(index + 1).padStart(2, "0")}
                            </span>
                            <div>
                              <h3>{chunk.path}</h3>
                              <p>
                                {[
                                  chunk.language,
                                  chunk.symbolName,
                                  chunk.symbolType,
                                ]
                                  .filter(Boolean)
                                  .join(" · ")}
                              </p>
                            </div>
                          </div>
                          <span className="similarity-score">
                            Similarity{" "}
                            <strong>{similarityLabel(chunk.similarity)}</strong>
                          </span>
                        </header>
                        <div className="code-result-toolbar">
                          <span>
                            Lines {chunk.startLine}–{chunk.endLine}
                          </span>
                          <CopyButton text={chunk.content} label="Copy code" />
                        </div>
                        <pre
                          tabIndex={0}
                          aria-label={`Code from ${chunk.path}`}
                        >
                          <code>{chunk.content}</code>
                        </pre>
                      </article>
                    ))
                  ) : (
                    <div className="panel empty-state">
                      <Search size={28} />
                      <h2>No semantically relevant code was found.</h2>
                      <p>Try describing the behavior in a different way.</p>
                    </div>
                  )}
                </>
              )}
            </section>
          )}
        </>
      )}
    </div>
  );
}
