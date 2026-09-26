import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { reviewPullRequest } from "../../api/pullRequests";
import { ErrorMessage } from "../Feedback";
import SourceCard from "../intelligence/SourceCard";
import { intelligenceError } from "../../utils/intelligence";
import { pullRequestError } from "../../utils/pullRequests";
export function diffLineId(path, side, line) { return `pr-diff-${encodeURIComponent(path)}-${side}-${line}`; }
export function diffFileId(path) { return `pr-file-${encodeURIComponent(path)}`; }
export function reviewError(error) {
  const message = error.response?.data?.message;
  if (["Chat API key is not configured", "Chat provider configuration is invalid", "Structured chat workflow is not supported by this provider"].includes(message)) return "AI provider is not configured for this DevPilot instance.";
  const known = {
    "AI review time budget exceeded; retry with a smaller pull request": "Review reached its time limit. Try a smaller pull request.",
    "Repository must be indexed before AI review.": "Index repository before running AI review.",
    "Repository index changed; retry AI review": "Repository index changed during review. Refresh and try again.",
    "Chat provider returned an invalid review response": "AI review could not validate its findings. Please try again.",
    "Chat provider returned an invalid grounded response": "AI provider returned an invalid response. Please try again.",
    "Chat provider timed out or is unavailable": "AI provider timed out. Please try again.",
    "Chat provider rate limited; retry later": "AI provider is rate limited. Please try again later.",
    "Chat provider request failed": "AI provider request failed. Please try again later.",
  };
  return known[message] || (message?.startsWith("Embedding ") || message?.startsWith("Chat ") || error.code === "ECONNABORTED" ? intelligenceError(error) : pullRequestError(error));
}
export default function ReviewPanel({ pr, repo, onRefresh, onNavigate }) {
  const [review, setReview] = useState(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const flight = useRef(null), epoch = useRef(0);
  useEffect(() => {
    epoch.current++; flight.current?.abort(); flight.current = null;
    setBusy(false); setReview(null); setError("");
    // These refs hold request state, not DOM nodes; invalidate the latest request on cleanup.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    return () => { epoch.current++; flight.current?.abort(); flight.current = null; };
  }, [pr.repositoryId, pr.number, pr.baseSha, pr.headSha]);
  async function analyze() {
    if (flight.current || repo?.status !== "READY") return;
    const controller = new AbortController(), requestEpoch = ++epoch.current;
    flight.current = controller; setBusy(true); setError(""); setReview(null);
    try {
      const result = await reviewPullRequest(pr.repositoryId, pr.number, { expectedBaseSha: pr.baseSha, expectedHeadSha: pr.headSha }, controller.signal);
      if (epoch.current !== requestEpoch) return;
      if (result.repositoryId !== pr.repositoryId || result.pullRequestNumber !== pr.number || result.baseSha !== pr.baseSha || result.headSha !== pr.headSha || !Array.isArray(result.findings) || !result.stats) throw new Error("Invalid review response");
      setReview(result);
    } catch (err) { if (epoch.current === requestEpoch) setError(reviewError(err)); }
    finally { if (epoch.current === requestEpoch) { flight.current = null; setBusy(false); } }
  }
  function cancel() { epoch.current++; flight.current?.abort(); flight.current = null; setBusy(false); }

  return <section className="panel review-panel" aria-label="DevPilot Review">
    <div className="section-heading"><div><div className="eyebrow">AI REVIEW · READ ONLY</div><h2>DevPilot Review</h2></div>{repo?.status === "READY" && <button className="primary" onClick={analyze} disabled={busy}>{busy ? "Analyzing pull request..." : "Analyze Pull Request"}</button>}</div>
    {repo?.status !== "READY" && <p>Index repository before running AI review. <Link className="text-action" to={`/repositories/${pr.repositoryId}?section=indexing`}>Index repository →</Link></p>}
    {busy && <div className="review-loading" role="status"><p>Analyzing pull request...</p><button className="secondary-button" onClick={cancel}>Cancel review</button></div>}
    <ErrorMessage>{error}</ErrorMessage>
    {error && onRefresh && <button className="secondary-button" onClick={onRefresh}>Refresh pull request</button>}
    {review && <>
      <div className="review-overview"><span className={`review-severity severity-${review.riskLevel.toLowerCase()}`}>Overall Risk: {review.riskLevel}</span><span>{review.stats.findings} findings across {review.stats.filesAnalyzed} reviewed files</span></div>
      <div className="review-counts">{["critical", "high", "medium", "low"].map(level => <span key={level}>{review.stats[level]} {level.charAt(0).toUpperCase() + level.slice(1)}</span>)}</div>
      <p>{review.summary}</p>
      {!!review.warnings?.length && <div className="review-warnings" role="status">{review.warnings.map(w => <p key={w}>{w}</p>)}</div>}
      {!!review.skippedFiles?.length && <details className="review-skipped"><summary>{review.skippedFiles.length} skipped files</summary><ul>{review.skippedFiles.map(f => <li key={f.path}>{f.path} — {f.reason.replaceAll("_", " ").toLowerCase()}</li>)}</ul></details>}
      <div className="review-findings">{review.findings.map(finding => <article className="review-finding" key={finding.id}>
        <div className="pr-badges"><span className={`review-severity severity-${finding.severity.toLowerCase()}`}>{finding.severity}</span><span className="outline-badge">{finding.category}</span></div>
        <h3>{finding.title}</h3><button className="text-action review-location" onClick={() => onNavigate?.(finding.location)}>{finding.location.path} · Lines {finding.location.startLine}–{finding.location.endLine} · {finding.location.side === "LEFT" ? "Old side" : "New side"}</button>
        <p>{finding.description}</p><h4>Recommendation</h4><p>{finding.recommendation}</p>
        {!!finding.evidence.length && <details><summary>Related repository context</summary><div className="sources-grid">{finding.evidence.map((source, i) => <SourceCard key={source.chunkId} source={source} number={i + 1} />)}</div></details>}
      </article>)}</div>
      <p className="muted">AI review can miss issues; use this as an additional engineering signal.</p>
      <details className="review-snapshot"><summary>Review snapshot</summary><p>Model: {review.model}</p><p>Index: {review.indexCommitSha || "Unknown"} ({review.indexRelation})</p></details>
    </>}
  </section>;
}
