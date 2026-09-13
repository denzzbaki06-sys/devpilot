import { Link } from "react-router-dom";
import { FolderGit2, ChevronDown } from "lucide-react";
import { StatusBadge, RepositoryMetadata } from "../RepositoryUi";
export function RepositoryContext({ repo, kind }) {
  return (
    <section className="ai-repository-context" aria-label="Repository context">
      <div className="context-folder">
        <FolderGit2 size={21} />
      </div>
      <div className="context-identity">
        <Link to={`/repositories/${repo.id}`}>{repo.fullName}</Link>
        <RepositoryMetadata repo={repo} />
      </div>
      <StatusBadge status={repo.status} />
      <Link className="change-repository" to={`/${kind}?choose=true`}>
        Change repository <ChevronDown size={14} />
      </Link>
    </section>
  );
}
export function ReadyGuard({ repo, onRefresh }) {
  const copy = {
    CONNECTED: [
      "Index this repository before using AI intelligence.",
      "Index repository",
    ],
    INDEXING: [
      "Repository intelligence is being prepared.",
      "View indexing status",
    ],
    FAILED: ["Indexing failed. Reindex to continue.", "Retry indexing"],
  };
  const [message, action] = copy[repo.status] || [
    "Repository intelligence is not ready.",
    "View repository",
  ];
  return (
    <section className="panel empty-state">
      <div className="empty-icon">
        <FolderGit2 size={30} />
      </div>
      <StatusBadge status={repo.status} />
      <h2>{message}</h2>
      <p>AI requests become available after a complete index is ready.</p>
      <div className="guard-actions">
        <Link className="primary inline" to={`/repositories/${repo.id}`}>
          {action}
        </Link>
        <button className="secondary-button" onClick={onRefresh}>
          Refresh status
        </button>
      </div>
    </section>
  );
}
