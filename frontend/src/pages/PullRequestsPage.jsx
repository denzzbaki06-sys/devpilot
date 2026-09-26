import ReviewPanel, { diffFileId, diffLineId } from "../components/review/ReviewPanel";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { GitPullRequest, GitBranch, ExternalLink } from "lucide-react";
import { getPullRequest, getPullRequests } from "../api/pullRequests";
import { getRepository } from "../api/repositories";
import { LoadingPanel, PageHeading, RepositoryNavigation } from "../components/RepositoryUi";
import { ErrorMessage } from "../components/Feedback";
import { dateLabel } from "../utils/repositoryUx";
import { pullRequestError, safeGitHubLink } from "../utils/pullRequests";

export function PullRequestNavigation({ id }) {
  return <RepositoryNavigation id={id} active="pull-requests" />;
}
function ChangeCounts({ pr }) {
  return <span className="pr-counts">
    {pr.additions != null && <span className="pr-additions">+{pr.additions}</span>}
    {pr.deletions != null && <span className="pr-deletions">−{pr.deletions}</span>}
    {pr.changedFiles != null && <span>{pr.changedFiles} files</span>}
  </span>;
}
function State({ pr }) {
  return <span className="pr-badges"><span className="outline-badge">{pr.state.toUpperCase()}</span>{pr.draft && <span className="outline-badge">DRAFT</span>}</span>;
}
function Branches({ pr }) { return <span className="pr-branches"><GitBranch size={14} aria-hidden="true" />{pr.headBranch} <span aria-label="into">→</span> {pr.baseBranch}</span>; }
function GitHubLink({ url }) {
  const href = safeGitHubLink(url);
  return href ? <a className="text-action" href={href} target="_blank" rel="noopener noreferrer">Open on GitHub <ExternalLink size={14} /></a> : null;
}
export default function PullRequestsPage() {
  const { id, number } = useParams();
  return <PullRequestWorkspace key={`${id}:${number || "list"}`} id={id} number={number} />;
}
function PullRequestWorkspace({ id, number }) {
  const [page, setPage] = useState(1), [attempt, setAttempt] = useState(0);
  const [repo, setRepo] = useState(null), [data, setData] = useState(null), [error, setError] = useState("");
  useEffect(() => {
    let active = true; const controller = new AbortController();
    setData(null); setError("");
    async function load() {
      try {
        const repository = await getRepository(id, controller.signal);
        if (!active) return;
        setRepo(repository);
        const result = number ? await getPullRequest(id, number, controller.signal) : await getPullRequests(id, page, 20, controller.signal);
        if (active) setData(result);
      } catch (err) { if (active) setError(pullRequestError(err)); }
    }
    load();
    return () => { active = false; controller.abort(); };
  }, [id, number, page, attempt]);
  return <>
    <Link className="back-link" to={number ? `/repositories/${id}/pull-requests` : `/repositories/${id}`}>← {number ? "Pull Requests" : repo?.fullName || "Repository"}</Link>
    <PageHeading eyebrow={repo?.fullName || "REPOSITORY WORKSPACE"} title="Pull Requests" description="Review changes with repository context." />
    <PullRequestNavigation id={id} />
    {error ? <section className="panel"><ErrorMessage>{error}</ErrorMessage><div className="pr-actions"><button className="secondary-button" onClick={() => setAttempt(v => v + 1)}>Try again</button><Link className="text-action" to="/github">Manage GitHub connection</Link></div></section>
      : !data ? <LoadingPanel label={number ? "Loading pull request…" : "Loading PRs…"} />
        : number ? <PullRequestDetail pr={data} repo={repo} onRefresh={() => setAttempt(v => v + 1)} /> : <>
          {!data.items.length ? <section className="panel empty-state"><GitPullRequest size={32} /><h2>{page === 1 ? "No open pull requests" : "No pull requests on this page"}</h2><p>Open pull requests from this repository will appear here.</p></section>
            : <div className="pr-list">{data.items.map(pr => <article className="panel pr-card" key={pr.number}>
              <div className="pr-card-title"><GitPullRequest size={22} aria-hidden="true" /><h2><Link to={`/repositories/${id}/pull-requests/${pr.number}`}><span className="muted">#{pr.number}</span> {pr.title}</Link></h2><State pr={pr} /></div>
              <Branches pr={pr} /><div className="pr-card-meta"><span>{pr.author || "Unknown author"} · Updated {dateLabel(pr.updatedAt)}</span><ChangeCounts pr={pr} /></div>
            </article>)}</div>}
          <nav className="pr-pagination" aria-label="Pull request pages"><button className="secondary-button" disabled={page === 1} onClick={() => setPage(v => v - 1)}>Previous</button><span>Page {page}</span><button className="secondary-button" disabled={!data.hasNext || page >= 1000} onClick={() => setPage(v => v + 1)}>Next</button></nav>
        </>}
  </>;
}
export function PullRequestDetail({ pr, repo, onRefresh }) {
  const [location, setLocation] = useState(null);
  return <>
    <section className="panel pr-detail">
      <State pr={pr} /><h2>#{pr.number} {pr.title}</h2><Branches pr={pr} />
      <div className="pr-card-meta"><span>{pr.author || "Unknown author"} · Updated {dateLabel(pr.updatedAt)}</span><ChangeCounts pr={pr} /></div>
      {pr.body && <details className="pr-body"><summary>Description</summary><pre>{pr.body}</pre></details>}
      <dl className="pr-shas"><dt>Base SHA</dt><dd>{pr.baseSha}</dd><dt>Head SHA</dt><dd>{pr.headSha}</dd><dt>Mergeable</dt><dd>{pr.mergeable == null ? "Unknown" : pr.mergeable ? "Yes" : "No"}</dd></dl>
      <div className="pr-actions"><GitHubLink url={pr.htmlUrl} /></div>
    </section>
    <ReviewPanel pr={pr} repo={repo} onRefresh={onRefresh} onNavigate={setLocation} />
    <div className="section-heading"><h2>Changed files</h2><span className="mono muted">{pr.statistics.patchAvailableCount} diffs available · {pr.statistics.patchUnavailableCount} unavailable</span></div>
    {pr.files.length === 0 && <p className="muted">No changed files.</p>}
    <div className="pr-files">{pr.files.map(file => <ChangedFile key={file.filename} file={file} focusLocation={location} />)}</div>
  </>;
}
export function ChangedFile({ file, focusLocation }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (focusLocation?.path === file.filename) setOpen(true);
  }, [focusLocation, file.filename]);
  useEffect(() => {
    if (!open || focusLocation?.path !== file.filename) return;
    const line = document.getElementById(diffLineId(file.filename, focusLocation.side, focusLocation.startLine));
    line?.scrollIntoView({ behavior: "smooth", block: "center" });
    line?.focus({ preventScroll: true });
  }, [open, focusLocation, file.filename]);
  return <details open={open} id={diffFileId(file.filename)} tabIndex={-1} className="pr-file" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary><span className="pr-file-path">{file.filename}{file.previousFilename && <small>Previously: {file.previousFilename}</small>}</span><span className="outline-badge">{file.status}</span><ChangeCounts pr={file} /></summary>
    {open && (!file.patchAvailable ? <p className="pr-diff-note">Diff preview unavailable for this file.</p>
      : file.parseStatus !== "PARSED" ? <><p className="pr-diff-note">Patch is incomplete or malformed. Line references are unavailable.</p><pre className="pr-raw-diff" tabIndex={0}>{file.patch}</pre></>
        : <div className="pr-diff-scroll" role="region" aria-label={`Diff for ${file.filename}`} tabIndex={0}><table className="pr-diff"><thead><tr><th>Old</th><th>New</th><th>Change</th><th>Code</th></tr></thead><tbody>
          {file.hunks.map((hunk, h) => [<tr className="pr-hunk" key={`h${h}`}><td colSpan={4}>@@ -{hunk.oldStart},{hunk.oldCount} +{hunk.newStart},{hunk.newCount} @@</td></tr>,
            ...hunk.lines.map((line, i) => <tr id={line.type === "CONTEXT" ? undefined : diffLineId(file.filename, line.type === "ADDITION" ? "RIGHT" : "LEFT", line.type === "ADDITION" ? line.newLineNumber : line.oldLineNumber)} tabIndex={-1} className={`diff-${line.type.toLowerCase()}`} key={`${h}-${i}`}><td>{line.oldLineNumber ?? ""}</td><td>{line.newLineNumber ?? ""}</td><td aria-label={line.type.toLowerCase()}>{line.type === "ADDITION" ? "+" : line.type === "DELETION" ? "−" : " "}</td><td><code>{line.content || " "}</code></td></tr>)])}
        </tbody></table></div>)}
  </details>;
}
