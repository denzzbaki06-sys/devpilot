import { FolderGit2, GitBranch, LockKeyhole, Globe } from "lucide-react";
import { Link } from "react-router-dom";
export function StatusBadge({ status }) {
  return (
    <span className={`status-badge status-${status?.toLowerCase()}`}>
      <span aria-hidden="true" /> {status || "UNKNOWN"}
    </span>
  );
}
export function Visibility({ privateRepository }) {
  const Icon = privateRepository ? LockKeyhole : Globe;
  return (
    <span className="repo-meta-item">
      <Icon size={13} />
      {privateRepository ? "Private" : "Public"}
    </span>
  );
}
export function RepositoryMetadata({ repo }) {
  return (
    <div className="repo-metadata">
      <span className="language-dot" />
      {repo.primaryLanguage || repo.language || "Unspecified language"}
      <Visibility privateRepository={repo.privateRepository} />
      <span className="repo-meta-item">
        <GitBranch size={13} />
        {repo.defaultBranch || "No default branch"}
      </span>
    </div>
  );
}
export function RepositoryCard({ repo }) {
  return (
    <article className="repository-card">
      <div className="repo-card-top">
        <FolderGit2 size={23} />
        <StatusBadge status={repo.status} />
      </div>
      <h2>
        <Link to={`/repositories/${repo.id}`}>{repo.fullName}</Link>
      </h2>
      <RepositoryMetadata repo={repo} />
      <Link className="text-action" to={`/repositories/${repo.id}`}>
        Open repository <span aria-hidden="true">→</span>
      </Link>
    </article>
  );
}
export function LoadingPanel({ label = "Loading workspace…" }) {
  return (
    <div className="loading-panel" role="status">
      <div className="skeleton line" />
      <div className="skeleton panel" />
      <p>{label}</p>
    </div>
  );
}
export function PageHeading({ eyebrow, title, description, children }) {
  return (
    <div className="page-heading">
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <h1>{title}</h1>
        <p className="muted">{description}</p>
      </div>
      {children}
    </div>
  );
}

export function RepositoryNavigation({ id, active }) {
  const sections = [["overview", "Overview", ""], ["indexing", "Indexing", "?section=indexing"], ["pull-requests", "Pull Requests", "/pull-requests"], ["architecture", "Architecture", "/architecture"], ["ask", "Ask", "/ask"], ["search", "Search", "/search"]];
  return <nav className="detail-tabs" aria-label="Repository sections">
    {sections.map(([key, label, suffix]) => <Link key={key} to={`/repositories/${id}${suffix}`} aria-current={active === key ? "page" : undefined}>{label}</Link>)}
  </nav>;
}
