import { useEffect, useState } from "react";
import { Link, Navigate, useSearchParams } from "react-router-dom";
import { FolderGit2, ArrowUpRight, Sparkles, Search } from "lucide-react";
import { getConnectedRepositories } from "../../api/repositories";
import { repositoryError } from "../../utils/repositoryUx";
import {
  PageHeading,
  StatusBadge,
  RepositoryMetadata,
  LoadingPanel,
} from "../RepositoryUi";
import { ErrorMessage } from "../Feedback";
export default function RepositoryPicker({ kind }) {
  const [repos, setRepos] = useState(null),
    [error, setError] = useState(""),
    [attempt, setAttempt] = useState(0),
    [params] = useSearchParams();
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setRepos(null);
    setError("");
    getConnectedRepositories(controller.signal)
      .then((data) => {
        if (active) setRepos(data.filter((r) => r.status === "READY"));
      })
      .catch((err) => {
        if (active) setError(repositoryError(err));
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [attempt]);
  const Icon = kind === "ask" ? Sparkles : Search;
  if (repos?.length === 1 && !params.has("choose"))
    return <Navigate to={`/repositories/${repos[0].id}/${kind}`} replace />;
  return (
    <>
      <PageHeading
        eyebrow="REPOSITORY INTELLIGENCE"
        title={kind === "ask" ? "Ask DevPilot" : "Semantic Search"}
        description="Choose the codebase you want to understand."
      />
      <ErrorMessage>{error}</ErrorMessage>
      {error && (
        <button
          className="secondary-button"
          onClick={() => setAttempt((v) => v + 1)}
        >
          Try again
        </button>
      )}
      {!repos && !error ? (
        <LoadingPanel label="Finding ready repositories…" />
      ) : repos?.length ? (
        <>
          <div className="section-heading">
            <h2>Choose a repository</h2>
            <span className="mono muted">{repos.length} READY</span>
          </div>
          <div className="repository-grid picker-grid">
            {repos.map((repo) => (
              <Link
                key={repo.id}
                className="repository-card picker-card"
                to={`/repositories/${repo.id}/${kind}`}
              >
                <div className="repo-card-top">
                  <FolderGit2 size={23} />
                  <StatusBadge status="READY" />
                </div>
                <h2>{repo.fullName}</h2>
                <RepositoryMetadata repo={repo} />
                <div className="picker-action">
                  <Icon size={16} />
                  {kind === "ask"
                    ? "Ask this repository"
                    : "Search this repository"}
                  <ArrowUpRight size={16} />
                </div>
              </Link>
            ))}
          </div>
        </>
      ) : repos ? (
        <section className="panel empty-state">
          <div className="empty-icon">
            <Icon size={28} />
          </div>
          <h2>No ready repositories yet.</h2>
          <p>Index a repository to unlock AI workspace.</p>
          <Link className="primary inline" to="/repositories">
            Go to repositories →
          </Link>
        </section>
      ) : null}
    </>
  );
}
