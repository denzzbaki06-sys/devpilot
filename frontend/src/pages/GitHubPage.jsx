import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import {
  Github,
  RefreshCw,
  Search,
  ArrowUpRight,
  Unplug,
  FolderGit2,
} from "lucide-react";
import {
  getGitHubStatus,
  getGitHubRepositories,
  startGitHubConnect,
  disconnectGitHub,
} from "../api/github";
import {
  getConnectedRepositories,
  connectRepository,
} from "../api/repositories";
import {
  repositoryError,
  dateLabel,
  oauthNotice,
  authorizationDestination,
} from "../utils/repositoryUx";
import { ErrorMessage } from "../components/Feedback";
import {
  PageHeading,
  LoadingPanel,
  RepositoryMetadata,
} from "../components/RepositoryUi";
import ConfirmDialog from "../components/ConfirmDialog";
export default function GitHubPage() {
  const [params, setParams] = useSearchParams(),
    [notice] = useState(() => oauthNotice(params.toString()));
  const [status, setStatus] = useState(null),
    [repos, setRepos] = useState([]),
    [connected, setConnected] = useState([]);
  const [loading, setLoading] = useState(true),
    [listLoading, setListLoading] = useState(false),
    [error, setError] = useState(""),
    [listError, setListError] = useState("");
  const [version, setVersion] = useState(0),
    [busy, setBusy] = useState(null),
    [confirm, setConfirm] = useState(false),
    [query, setQuery] = useState(""),
    [filter, setFilter] = useState("all");
  useEffect(() => {
    if (params.has("connected") || params.has("error")) {
      const next = new URLSearchParams(params);
      next.delete("connected");
      next.delete("error");
      setParams(next, { replace: true });
    }
  }, [params, setParams]);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    setListError("");
    setRepos([]);
    async function load() {
      try {
        const [current, local] = await Promise.all([
          getGitHubStatus(controller.signal),
          getConnectedRepositories(controller.signal),
        ]);
        if (!active) return;
        setStatus(current);
        setConnected(local);
        setLoading(false);
        if (current.connected) {
          setListLoading(true);
          try {
            const remote = await getGitHubRepositories(controller.signal);
            if (active) setRepos(remote);
          } catch (err) {
            if (active) setListError(repositoryError(err));
          } finally {
            if (active) setListLoading(false);
          }
        }
      } catch (err) {
        if (active) {
          setError(repositoryError(err));
          setLoading(false);
        }
      }
    }
    load();
    return () => {
      active = false;
      controller.abort();
    };
  }, [version]);
  async function connect() {
    setBusy("oauth");
    setError("");
    try {
      const result = await startGitHubConnect();
      window.location.assign(authorizationDestination(result.authorizeUrl));
    } catch (err) {
      setError(repositoryError(err));
      setBusy(null);
    }
  }
  async function disconnect() {
    setBusy("disconnect");
    setError("");
    try {
      await disconnectGitHub();
      setConfirm(false);
      setStatus({ connected: false, login: null });
      setRepos([]);
      setVersion((v) => v + 1);
    } catch (err) {
      setError(repositoryError(err));
      setConfirm(false);
    } finally {
      setBusy(null);
    }
  }
  async function add(id) {
    setBusy(id);
    setListError("");
    try {
      const repo = await connectRepository(id);
      setConnected((rows) => [...rows.filter((r) => r.id !== repo.id), repo]);
    } catch (err) {
      if (err.response?.status === 409) {
        try {
          const local = await getConnectedRepositories();
          setConnected(local);
          if (!local.some((r) => r.githubRepositoryId === id))
            setListError(repositoryError(err));
        } catch (failure) {
          setListError(repositoryError(failure));
        }
      } else setListError(repositoryError(err));
    } finally {
      setBusy(null);
    }
  }
  const visible = repos.filter(
    (repo) =>
      repo.fullName.toLowerCase().includes(query.toLowerCase()) &&
      (filter === "all" || repo.privateRepository === (filter === "private")),
  );
  return (
    <>
      <PageHeading
        eyebrow="SOURCE CONNECTION"
        title="Your code. Connected."
        description="Bring your GitHub repositories into your DevPilot workspace."
      />
      {notice && (
        <div
          className={notice.type === "error" ? "error" : "notice"}
          role="status"
        >
          {notice.text}
        </div>
      )}
      <ErrorMessage>{error}</ErrorMessage>
      {error && !status && (
        <button
          className="secondary-button"
          onClick={() => setVersion((v) => v + 1)}
        >
          Try again
        </button>
      )}
      {loading ? (
        <LoadingPanel label="Checking GitHub connection…" />
      ) : status?.connected ? (
        <>
          <section className="connection-panel">
            <div className="connection-icon">
              <Github size={32} />
            </div>
            <div className="connection-copy">
              <span className="status-badge status-ready">Connected</span>
              <h2>{status.login}</h2>
              <p>Choose the repositories you want to understand better.</p>
            </div>
            <div className="connection-actions">
              <a className="primary inline" href="#repository-browser">
                Browse repositories <ArrowUpRight size={16} />
              </a>
              <button
                className="secondary-button"
                disabled={!!busy || listLoading}
                onClick={() => setVersion((v) => v + 1)}
              >
                <RefreshCw size={15} />
                Refresh repositories
              </button>
              <button
                className="text-button"
                disabled={!!busy || listLoading}
                onClick={() => setConfirm(true)}
              >
                <Unplug size={14} />
                Disconnect
              </button>
            </div>
          </section>
          <section id="repository-browser" className="panel browser-panel">
            <div className="section-heading">
              <h2>Available repositories</h2>
              <span className="mono muted">
                {listLoading ? "LOADING" : `${repos.length} AVAILABLE`}
              </span>
            </div>
            <div className="browser-toolbar">
              <label className="search-input">
                <Search size={17} />
                <span className="sr-only">Search repositories</span>
                <input
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Find a repository…"
                />
              </label>
              <div className="filter-group" aria-label="Repository visibility">
                {["all", "public", "private"].map((value) => (
                  <button
                    key={value}
                    aria-pressed={filter === value}
                    onClick={() => setFilter(value)}
                  >
                    {value[0].toUpperCase() + value.slice(1)}
                  </button>
                ))}
              </div>
            </div>
            <ErrorMessage>{listError}</ErrorMessage>
            {listError && (
              <button
                className="secondary-button"
                onClick={() => setVersion((v) => v + 1)}
              >
                Retry repository list
              </button>
            )}
            {listLoading ? (
              <LoadingPanel label="Fetching your GitHub repositories…" />
            ) : !listError && visible.length === 0 ? (
              <div className="empty-state">
                <FolderGit2 size={32} />
                <h2>
                  {repos.length
                    ? "No matching repositories."
                    : "No repositories available."}
                </h2>
                <p>
                  {repos.length
                    ? "Try another name or visibility filter."
                    : "GitHub has not returned any accessible repositories for this account."}
                </p>
              </div>
            ) : (
              <div className="github-repo-list">
                {visible.map((repo) => {
                  const local = connected.find(
                    (r) => r.githubRepositoryId === repo.githubRepositoryId,
                  );
                  return (
                    <article
                      className="github-repo-row"
                      key={repo.githubRepositoryId}
                    >
                      <div className="repo-row-icon">
                        <FolderGit2 size={21} />
                      </div>
                      <div className="repo-row-content">
                        <h3>{repo.fullName}</h3>
                        <RepositoryMetadata repo={repo} />
                        <small className="muted">
                          Updated {dateLabel(repo.updatedAt)}
                        </small>
                      </div>
                      {local ? (
                        <Link
                          className="secondary-button"
                          to={`/repositories/${local.id}`}
                        >
                          Open repository →
                        </Link>
                      ) : (
                        <button
                          className="primary inline"
                          disabled={busy !== null}
                          onClick={() => add(repo.githubRepositoryId)}
                        >
                          {busy === repo.githubRepositoryId
                            ? "Adding…"
                            : "Add to DevPilot"}
                        </button>
                      )}
                    </article>
                  );
                })}
              </div>
            )}
          </section>
        </>
      ) : status && !status.connected ? (
        <section className="panel github-disconnected">
          <div className="empty-icon">
            <Github size={34} />
          </div>
          <span className="outline-badge">NOT CONNECTED</span>
          <h2>Connect GitHub</h2>
          <p>
            Connect your GitHub account to import repositories into DevPilot.
          </p>
          <button
            className="primary inline"
            disabled={!!busy}
            onClick={connect}
          >
            {busy === "oauth" ? "Opening GitHub…" : "Connect GitHub"}
            <ArrowUpRight size={17} />
          </button>
          <div className="connection-facts">
            <p>
              <strong>Public and private repositories</strong>
              <br />
              Browse repositories available to your GitHub account.
            </p>
            <p>
              <strong>Context for repository analysis</strong>
              <br />
              Select which repositories to add and index in DevPilot.
            </p>
            <p>
              <strong>Tokens stay out of the interface</strong>
              <br />
              DevPilot does not display your GitHub access token.
            </p>
          </div>
        </section>
      ) : null}
      {confirm && (
        <ConfirmDialog
          title="Disconnect GitHub?"
          confirmLabel="Disconnect GitHub"
          onCancel={() => setConfirm(false)}
          onConfirm={disconnect}
          busy={busy === "disconnect"}
        >
          <p>
            DevPilot will remove the locally stored GitHub connection. Connected
            repositories and their existing indexes remain in DevPilot.
          </p>
          <p>
            To revoke GitHub authorization itself, use your GitHub application
            settings. Reconnect before importing or indexing again.
          </p>
        </ConfirmDialog>
      )}
    </>
  );
}
