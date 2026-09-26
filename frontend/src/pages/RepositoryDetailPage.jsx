import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import {
  GitBranch,
  FolderGit2,
  Layers,
  Play,
  RefreshCw,
  Trash2,
  CheckCircle2,
  Sparkles,
  Search,
} from "lucide-react";
import {
  getRepository,
  removeRepository,
  startIndexing,
} from "../api/repositories";
import { useIndexStatus } from "../hooks/useIndexStatus";
import { repositoryError, dateLabel } from "../utils/repositoryUx";
import { ErrorMessage } from "../components/Feedback";
import {
  PageHeading,
  LoadingPanel,
  StatusBadge,
  RepositoryMetadata,
} from "../components/RepositoryUi";
import ConfirmDialog from "../components/ConfirmDialog";
export default function RepositoryDetailPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get("section") === "indexing" ? "indexing" : "overview";
  function setTab(next) {
    setSearchParams(current => {
      const params = new URLSearchParams(current);
      if (next === "indexing") params.set("section", "indexing");
      else params.delete("section");
      return params;
    });
  }
  const { id } = useParams(),
    navigate = useNavigate(),
    index = useIndexStatus(id);
  const [repo, setRepo] = useState(null),
    [error, setError] = useState(""),
    [version, setVersion] = useState(0),
    [busy, setBusy] = useState(null),
    [confirm, setConfirm] = useState(false);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setRepo(null);
    setError("");
    getRepository(id, controller.signal)
      .then((data) => {
        if (active) setRepo(data);
      })
      .catch((err) => {
        if (active) setError(repositoryError(err));
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [id, version]);
  async function start() {
    setBusy("index");
    setError("");
    try {
      await startIndexing(id);
      setRepo((current) => ({ ...current, status: "INDEXING" }));
      index.refresh();
      setTab("indexing");
    } catch (err) {
      setError(repositoryError(err));
      if (err.response?.status === 409) index.refresh();
    } finally {
      setBusy(null);
    }
  }
  async function remove() {
    setBusy("remove");
    setError("");
    try {
      await removeRepository(id);
      navigate("/repositories", { replace: true });
    } catch (err) {
      setError(repositoryError(err));
      setConfirm(false);
      if (err.response?.status === 409) index.refresh();
    } finally {
      setBusy(null);
    }
  }
  if (!repo)
    return (
      <>
        <ErrorMessage>{error}</ErrorMessage>
        {error ? (
          <>
            <button
              className="secondary-button"
              onClick={() => setVersion((v) => v + 1)}
            >
              Try again
            </button>
            <Link className="text-action" to="/repositories">
              Back to repositories
            </Link>
          </>
        ) : (
          <LoadingPanel label="Loading repository…" />
        )}
      </>
    );
  const status = index.data?.status || repo.status,
    summary = index.data?.job?.summary;
  return (
    <>
      <Link className="back-link" to="/repositories">
        ← Repositories
      </Link>
      <PageHeading
        eyebrow="REPOSITORY WORKSPACE"
        title={repo.fullName}
        description="A clearer picture, built from your source."
      >
        <StatusBadge status={status} />
      </PageHeading>
      <RepositoryMetadata repo={repo} />
      <div
        className="detail-tabs"
        role="group"
        aria-label="Repository sections"
      >
        <button
          aria-pressed={tab === "overview"}
          onClick={() => setTab("overview")}
        >
          Overview
        </button>
        <button
          aria-pressed={tab === "indexing"}
          onClick={() => setTab("indexing")}
        >
          Indexing
        </button>
        <Link to={`/repositories/${id}/pull-requests`}>Pull Requests</Link>
        <Link to={`/repositories/${id}/architecture`}>Architecture</Link>
        <Link to={`/repositories/${id}/ask`}>
          <Sparkles size={14} />
          Ask
        </Link>
        <Link to={`/repositories/${id}/search`}>
          <Search size={14} />
          Search
        </Link>
      </div>
      <ErrorMessage>{error}</ErrorMessage>
      {tab === "overview" && (
        <section className="panel">
          <div className="section-heading">
            <h2>Repository overview</h2>
            <FolderGit2 size={20} />
          </div>
          <dl className="metadata-grid">
            <Metadata
              label="Connected at"
              value={dateLabel(repo.connectedAt)}
            />
            <Metadata
              label="GitHub updated"
              value={dateLabel(repo.githubUpdatedAt)}
            />
            <Metadata
              label="Language"
              value={repo.primaryLanguage || "Not specified"}
            />
            <Metadata
              label="Default branch"
              value={repo.defaultBranch || "Not available"}
            />
            <Metadata
              label="Visibility"
              value={repo.privateRepository ? "Private" : "Public"}
            />
            <Metadata label="Status" value={status} />
          </dl>
        </section>
      )}
      <section className="panel indexing-panel">
        <div className="section-heading">
          <h2>
            <Layers size={19} />
            Repository indexing
          </h2>
          {!index.error && status === "INDEXING" && (
            <span className="status-badge status-indexing" role="status">
              {index.data?.job?.status || "INDEXING"}
            </span>
          )}
        </div>
        <ErrorMessage>{index.error}</ErrorMessage>
        {index.error && (
          <button className="secondary-button" onClick={index.refresh}>
            Retry status
          </button>
        )}
        {index.loading ? (
          <LoadingPanel label="Checking index status…" />
        ) : (
          <>
            <div className={`index-banner banner-${status.toLowerCase()}`}>
              <div>
                {status === "READY" ? (
                  <CheckCircle2 size={24} />
                ) : (
                  <GitBranch size={24} />
                )}
              </div>
              <div>
                <h3>
                  {status === "READY"
                    ? "Repository intelligence is ready."
                    : status === "INDEXING"
                      ? "Building your repository context."
                      : status === "FAILED"
                        ? "Indexing could not be completed."
                        : "Give your codebase some context."}
                </h3>
                <p>
                  {status === "INDEXING"
                    ? "The index runs in the background. Status refreshes every 2.5 seconds."
                    : status === "READY"
                      ? "Your published index is available. Ask questions or search the code by meaning."
                      : status === "FAILED"
                        ? "Review the last attempt below, then try indexing again."
                        : "Index source files to prepare repository intelligence."}
                </p>
              </div>
            </div>
            {status === "FAILED" && index.data?.lastError && (
              <ErrorMessage>{index.data.lastError}</ErrorMessage>
            )}
            <div className="index-controls">
              <button
                className="primary inline"
                disabled={!!busy || status === "INDEXING" || !!index.error}
                onClick={start}
              >
                {status === "READY" ? (
                  <RefreshCw size={16} />
                ) : (
                  <Play size={16} />
                )}{" "}
                {busy === "index"
                  ? "Starting…"
                  : status === "INDEXING"
                    ? "Indexing…"
                    : status === "READY"
                      ? "Reindex repository"
                      : status === "FAILED"
                        ? "Try again"
                        : "Index repository"}
              </button>
              {status === "READY" && (
                <>
                  <Link
                    className="secondary-button"
                    to={`/repositories/${id}/ask`}
                  >
                    Ask DevPilot →
                  </Link>
                  <Link
                    className="secondary-button"
                    to={`/repositories/${id}/search`}
                  >
                    Semantic Search →
                  </Link>
                </>
              )}
            </div>
            {index.data && (
              <>
                <div className="section-heading">
                  <h3>Published index</h3>
                  <span className="mono muted">
                    {index.data.lastIndexedAt
                      ? dateLabel(index.data.lastIndexedAt)
                      : "NO PUBLISHED INDEX"}
                  </span>
                </div>
                <div className="index-stats">
                  <Count
                    label="Files indexed"
                    value={index.data.filesIndexed}
                  />
                  <Count
                    label="Chunks created"
                    value={index.data.chunksCreated}
                  />
                  <Count
                    label="Embeddings created"
                    value={index.data.embeddingsCreated}
                  />
                  <Count label="Lines indexed" value={index.data.lineCount} />
                </div>
                <p className="model-label">
                  Embedding model{" "}
                  <code>{index.data.embeddingModel || "Not available"}</code>
                </p>
                {summary && (
                  <details
                    className="attempt-details"
                    open={status === "FAILED"}
                  >
                    <summary>Latest attempt · {index.data.job.status}</summary>
                    <p className="muted">
                      Attempt totals are saved when the job finishes. These are
                      not a live progress estimate.
                    </p>
                    <div className="index-stats">
                      <Count
                        label="Files discovered"
                        value={summary.filesDiscovered}
                      />
                      <Count
                        label="Files indexed"
                        value={summary.filesIndexed}
                      />
                      <Count
                        label="Files skipped"
                        value={summary.filesSkipped}
                      />
                      <Count
                        label="Chunks created"
                        value={summary.chunksCreated}
                      />
                      <Count
                        label="Embeddings created"
                        value={summary.embeddingsCreated}
                      />
                      <Count
                        label="Embeddings reused"
                        value={summary.embeddingsReused}
                      />
                    </div>
                    <p className="model-label">
                      Attempt model{" "}
                      <code>{summary.embeddingModel || "Not available"}</code>
                    </p>
                  </details>
                )}
              </>
            )}
          </>
        )}
      </section>
      <section className="danger-zone">
        <div>
          <h3>Remove this connection</h3>
          <p>
            This removes the repository from DevPilot. It does not delete
            anything from GitHub.
          </p>
        </div>
        <button
          className="danger-button"
          disabled={
            !!busy || status === "INDEXING" || index.loading || !!index.error
          }
          onClick={() => setConfirm(true)}
        >
          <Trash2 size={15} />
          Remove from DevPilot
        </button>
      </section>
      {confirm && (
        <ConfirmDialog
          title="Remove repository from DevPilot?"
          confirmLabel="Remove repository"
          onCancel={() => setConfirm(false)}
          onConfirm={remove}
          busy={busy === "remove"}
        >
          <p>
            This removes the repository and its indexed data from DevPilot. It
            does not delete anything from GitHub.
          </p>
        </ConfirmDialog>
      )}
    </>
  );
}
function Metadata({ label, value }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
function Count({ label, value }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value ?? "—"}</strong>
    </div>
  );
}
