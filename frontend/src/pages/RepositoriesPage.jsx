import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { FolderGit2, Plus } from "lucide-react";
import { getConnectedRepositories } from "../api/repositories";
import { getGitHubStatus } from "../api/github";
import { repositoryError } from "../utils/repositoryUx";
import {
  PageHeading,
  LoadingPanel,
  RepositoryCard,
} from "../components/RepositoryUi";
import { ErrorMessage } from "../components/Feedback";
export default function RepositoriesPage() {
  const [data, setData] = useState(null),
    [error, setError] = useState(""),
    [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true,
      timer;
    const controller = new AbortController();
    setError("");
    setData(null);
    async function load() {
      try {
        const [repos, github] = await Promise.all([
          getConnectedRepositories(controller.signal),
          getGitHubStatus(controller.signal),
        ]);
        if (!active) return;
        setData({ repos, github });
        if (repos.some((r) => r.status === "INDEXING"))
          timer = setTimeout(load, 2500);
      } catch (err) {
        if (active) setError(repositoryError(err));
      }
    }
    load();
    return () => {
      active = false;
      clearTimeout(timer);
      controller.abort();
    };
  }, [version]);
  return (
    <>
      <PageHeading
        eyebrow="YOUR CODEBASES"
        title="A home for your repositories."
        description="Connect a repository. Build its context. Find your next insight."
      >
        <Link className="primary inline" to="/github">
          <Plus size={17} />
          Add repository
        </Link>
      </PageHeading>
      <ErrorMessage>{error}</ErrorMessage>
      {error && (
        <button
          className="secondary-button"
          onClick={() => setVersion((v) => v + 1)}
        >
          Try again
        </button>
      )}
      {!data && !error ? (
        <LoadingPanel label="Loading connected repositories…" />
      ) : data?.repos.length ? (
        <div className="repository-grid">
          {data.repos.map((repo) => (
            <RepositoryCard key={repo.id} repo={repo} />
          ))}
        </div>
      ) : data ? (
        <section className="panel empty-state">
          <div className="empty-icon">
            <FolderGit2 size={30} />
          </div>
          <h2>
            {data.github.connected
              ? "Choose a repository to analyze."
              : "Connect GitHub first."}
          </h2>
          <p>
            {data.github.connected
              ? "Add a repository from your GitHub account to start building context."
              : "Connect your account to discover the repositories you can bring into DevPilot."}
          </p>
          <Link className="primary inline" to="/github">
            {data.github.connected ? "Browse repositories" : "Connect GitHub"}
          </Link>
        </section>
      ) : null}
    </>
  );
}
