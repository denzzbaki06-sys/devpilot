import { Component, lazy, Suspense, useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Boxes, ArrowRight, Code2, GitBranch } from "lucide-react";
import { getArchitecture } from "../api/architecture";
import { getRepository } from "../api/repositories";
import { PageHeading, LoadingPanel, RepositoryNavigation } from "../components/RepositoryUi";
import { ErrorMessage } from "../components/Feedback";
import { repositoryError } from "../utils/repositoryUx";

import ArchitectureAiPanel from "../components/architecture/ArchitectureAiPanel";

const labels = { CONTROLLER: "Controllers", SERVICE: "Services", REPOSITORY: "Repositories", ENTITY: "Entities", CONFIGURATION: "Configuration", SECURITY: "Security", COMPONENT: "Components", UTILITY: "Utilities", ENTRY_POINT: "Application entry points", EXTERNAL_CLIENT: "External clients", MODULE: "Modules", FUNCTION: "Functions", UNKNOWN: "Other" };
export default function ArchitecturePage() {
  const { id } = useParams();
  return <ArchitectureWorkspace key={id} id={id} />;
}
export function ArchitectureWorkspace({ id }) {
  const [repo, setRepo] = useState(null), [data, setData] = useState(null), [error, setError] = useState(""), [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true; const controller = new AbortController();
    setData(null); setRepo(null); setError("");
    async function load() {
      try {
        const repository = await getRepository(id, controller.signal);
        if (!active) return;
        setRepo(repository);
        if (repository.status !== "READY") return;
        const result = await getArchitecture(id, controller.signal);
        if (active) {
          if (String(result.repositoryId) !== String(id) || !Array.isArray(result.components) || !Array.isArray(result.relationships) || !result.stats) throw new Error("Invalid architecture response");
          setData(result);
        }
      } catch (err) {
        if (!active) return;
        const message = err.response?.data?.message;
        setError(message === "Repository must be indexed before architecture analysis." ? "Repository must be indexed before architecture analysis." : message === "Repository index changed; retry architecture analysis" ? "The repository index changed. Reload to analyze the latest snapshot." : repositoryError(err));
      }
    }
    load(); return () => { active = false; controller.abort(); };
  }, [id, attempt]);
  return <>
    <Link className="back-link" to={`/repositories/${id}`}>← {repo?.fullName || "Repository"}</Link>
    <PageHeading eyebrow="REPOSITORY WORKSPACE" title="Architecture" description="Explore the structure and dependencies of this repository." />
    <RepositoryNavigation id={id} active="architecture" />
    {error ? <section className="panel"><ErrorMessage>{error}</ErrorMessage><button className="secondary-button" onClick={() => setAttempt(v => v + 1)}>Try again</button></section>
      : repo && repo.status !== "READY" ? <section className="panel empty-state"><Boxes size={32} /><h2>Index repository before architecture analysis.</h2><p>Architecture uses the published source snapshot.</p><Link className="primary inline" to={`/repositories/${id}?section=indexing`}>Index repository</Link><button className="secondary-button" onClick={() => setAttempt(v => v + 1)}>Refresh status</button></section>
        : !data ? <LoadingPanel label="Analyzing repository structure…" /> : <ArchitectureViews key={`${data.repositoryId}:${data.indexCommitSha || "unknown"}`} data={data} />}
  </>;
}
const ArchitectureGraph = lazy(() => import("../components/architecture/ArchitectureGraph"));
class GraphBoundary extends Component {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() { return this.state.failed ? <section className="panel" role="alert"><p>The graph could not be displayed. Architecture data is still available.</p><button className="secondary-button" onClick={this.props.onFallback}>Open Components view</button></section> : this.props.children; }
}
export function ArchitectureViews({ data }) {
  const [view, setView] = useState("graph");
  const [selectedComponentId, setSelectedComponentId] = useState(null), [navigation, setNavigation] = useState(null), [highlightedIds, setHighlightedIds] = useState([]);
  const navigate = useCallback(item => { setView("graph"); setNavigation({ ...item, token: Date.now() }); }, []);
  const highlight = useCallback(ids => { setHighlightedIds(ids); if (ids.length) setView("graph"); }, []);
  return <>
    <div className="architecture-mode-switch" role="group" aria-label="Architecture views">
      <button className="secondary-button" aria-pressed={view === "graph"} onClick={() => setView("graph")}>Graph</button>
      <button className="secondary-button" aria-pressed={view === "components"} onClick={() => setView("components")}>Components</button>
    </div>
    {view === "graph" ? <GraphBoundary onFallback={() => setView("components")}><Suspense fallback={<LoadingPanel label="Loading architecture map…" />}><ArchitectureGraph data={data} onContextChange={setSelectedComponentId} navigation={navigation} highlightedIds={highlightedIds} /></Suspense></GraphBoundary> : <ArchitectureResult data={data} />}
    <ArchitectureAiPanel data={data} selectedComponentId={selectedComponentId} onClearContext={() => setSelectedComponentId(null)} onNavigate={navigate} onHighlight={highlight} highlightedIds={highlightedIds} />
  </>;
}
export function ArchitectureResult({ data }) {
  const byId = new Map(data.components.map(c => [c.id, c]));
  const groups = [...new Set(data.components.map(c => c.type))].sort().map(type => [type, labels[type] || type]);
  return <div className="architecture-workspace">
    <div className="architecture-summary">{[["Components", data.stats.components, Boxes], ["Relationships", data.stats.relationships, GitBranch], ["Entry Points", data.stats.entryPoints, ArrowRight], ["Languages", data.stats.languages.length, Code2]].map(([label, value, Icon]) => <section className="panel" key={label}><Icon size={18} aria-hidden="true" /><strong>{value}</strong><span>{label}</span></section>)}</div>
    <p className="muted architecture-snapshot">Static source analysis · {data.stats.filesAnalyzed} files analyzed · {data.stats.filesSkipped} skipped<br />Index commit: <code>{data.indexCommitSha || "Unknown"}</code></p>
    {data.warnings.length > 0 && <aside className="architecture-warnings" aria-label="Analysis warnings"><h2>Analysis scope</h2><ul>{data.warnings.map(w => <li key={w}>{w}</li>)}</ul></aside>}
    {!data.components.length ? <section className="panel empty-state"><Boxes size={32} /><h2>No supported architecture components</h2><p>This indexed snapshot has no components supported by the current static analyzers.</p></section> : <>
      {data.entryPoints.length > 0 && <section className="panel"><h2>Detected entry points</h2><ul>{data.entryPoints.map(c => <li key={c.id}><a href={`#architecture-${c.id}`}>{c.name}</a> <span className="mono muted">{c.path} · Lines {c.startLine}–{c.endLine}</span></li>)}</ul></section>}
      {groups.map(([type, label]) => <section key={type} aria-label={label}><div className="section-heading"><h2>{label}</h2><span className="outline-badge">{data.components.filter(c => c.type === type).length}</span></div><div className="architecture-grid">{data.components.filter(c => c.type === type).map(c => <ComponentCard key={c.id} component={c} relationships={data.relationships} byId={byId} />)}</div></section>)}
      <section className="panel architecture-relationships"><h2>Relationships</h2>{!data.relationships.length ? <p className="muted">No resolved relationships in this scope.</p> : <ul>{data.relationships.map(r => <li key={`${r.sourceComponentId}:${r.targetComponentId}:${r.type}`}><strong>{byId.get(r.sourceComponentId)?.name}</strong><span className="outline-badge">{r.type}</span><ArrowRight size={15} aria-hidden="true" /><strong>{byId.get(r.targetComponentId)?.name}</strong><small>{r.evidence ? `${r.evidence.path} · Lines ${r.evidence.startLine}–${r.evidence.endLine}` : "No source evidence supplied"}</small></li>)}</ul>}</section>
      {data.externalDependencies.length > 0 && <section className="panel"><h2>External imports</h2><ul>{data.externalDependencies.map((d, i) => <li key={i}>{d.name} <span className="muted">{d.evidence.path}:{d.evidence.startLine}</span></li>)}</ul></section>}
    </>}
  </div>;
}
function ComponentCard({ component: c, relationships, byId }) {
  const outgoing = relationships.filter(r => r.sourceComponentId === c.id), incoming = relationships.filter(r => r.targetComponentId === c.id);
  return <article className="panel architecture-card" id={`architecture-${c.id}`}>
    <div className="architecture-card-title"><h3>{c.name}</h3><span className="outline-badge">{c.type}</span></div>
    <p className="mono architecture-path">{c.path}<br /><span className="muted">Lines {c.startLine}–{c.endLine} · {c.language}</span></p>
    <p><span className="muted">Dependencies: </span>{outgoing.length ? [...new Set(outgoing.map(r => byId.get(r.targetComponentId)?.name))].join(", ") : "None resolved"}</p>
    <details><summary>Component details · {c.name}</summary><dl><dt>Qualified name</dt><dd>{c.qualifiedName}</dd><dt>Symbol</dt><dd>{c.symbol}</dd></dl>
      <h4>Outgoing relationships</h4><ul>{outgoing.map(r => <li key={`${r.targetComponentId}:${r.type}`}>{r.type} → {byId.get(r.targetComponentId)?.name}</li>)}</ul>
      <h4>Incoming relationships</h4><ul>{incoming.map(r => <li key={`${r.sourceComponentId}:${r.type}`}>{byId.get(r.sourceComponentId)?.name} → {r.type}</li>)}</ul>
      {c.metadata.routes?.length > 0 && <><h4>Controller routes</h4><ul>{c.metadata.routes.map((r, i) => <li key={i}><code>{r.methods.join(", ")} {r.path}</code> · {r.symbol}</li>)}</ul></>}
      {c.metadata.tableName && <p>Table: <code>{c.metadata.tableName}</code></p>}
      {c.metadata.entityName && <p>Entity: {c.metadata.entityName}</p>}
      {c.metadata.beanMethods?.length > 0 && <p>Bean methods: {c.metadata.beanMethods.join(", ")}</p>}
      {c.metadata.annotations?.length > 0 && <p className="mono">Annotations: {c.metadata.annotations.join(", ")}</p>}
      {c.metadata.transport && <p>{c.metadata.transport}</p>}
    </details>
  </article>;
}
