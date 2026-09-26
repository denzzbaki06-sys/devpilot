import { useEffect, useRef, useState } from "react";
import { Copy, X } from "lucide-react";

export function SourceLocation({ source }) {
  const [message, setMessage] = useState("");
  if (!source?.path || !Number.isInteger(source.startLine) || !Number.isInteger(source.endLine)) return <p className="muted">No source location was supplied.</p>;
  const text = `${source.path}:${source.startLine}-${source.endLine}`;
  async function copy() {
    try { await navigator.clipboard.writeText(text); setMessage("Source location copied."); }
    catch { setMessage("Copy unavailable. Select the source text to copy it."); }
  }
  return <div className="arch-source"><button className="arch-source-button" onClick={copy} aria-label={`Copy source location ${text}`}><Copy size={14} aria-hidden="true" /><span>{source.path}<small>Lines {source.startLine}–{source.endLine}</small></span></button>{source.symbol && <p className="mono muted">Symbol: {source.symbol}</p>}<span role="status">{message}</span></div>;
}
export default function ArchitectureDetailPanel({ selection, graph, entryIds, onSelect, onClose }) {
  const heading = useRef(null);
  useEffect(() => { heading.current?.focus(); }, [selection?.id, selection?.kind]);
  const byId = new Map(graph.nodes.map(n => [n.id, n.data.component]));
  const edge = selection?.kind === "edge" ? graph.edges.find(e => e.id === selection.id) : null;
  const component = selection?.kind === "node" ? byId.get(selection.id) : null;
  if (!component && !edge) return null;
  const incoming = graph.edges.filter(e => e.target === component?.id), outgoing = graph.edges.filter(e => e.source === component?.id);
  const metadata = component?.metadata || {};
  return <aside className="arch-detail panel" aria-label={component ? "Component detail" : "Relationship detail"} onKeyDown={e => { if (e.key === "Escape") { e.stopPropagation(); onClose(); } }}>
    <div className="arch-detail-heading"><span className="eyebrow">{component ? "COMPONENT" : "RELATIONSHIP"}</span><button className="icon-button" aria-label="Close details" onClick={onClose}><X size={18} /></button></div>
    <h2 ref={heading} tabIndex={-1}>{component ? component.name : `${byId.get(edge.source)?.name} → ${byId.get(edge.target)?.name}`}</h2>
    {component ? <>
      <div className="arch-detail-badges"><span className="outline-badge">{component.type}</span><span className="outline-badge">{component.language}</span></div>
      <p className="mono muted">{component.qualifiedName}</p><SourceLocation key={component.id} source={component} />
      {entryIds.has(component.id) && <p className="arch-entry">Detected entry point</p>}
      <RelationshipList title="Incoming relationships" edges={incoming} byId={byId} incoming onSelect={onSelect} />
      <RelationshipList title="Outgoing relationships" edges={outgoing} byId={byId} onSelect={onSelect} />
      {metadata.routes?.length > 0 && <section><h3>Controller routes</h3><ul className="arch-routes">{metadata.routes.map((r, i) => <li key={i}><code>{r.methods.join(", ")} {r.path}</code><small>{r.symbol}</small></li>)}</ul></section>}
      {metadata.tableName && <p>Table: <code>{metadata.tableName}</code></p>}{metadata.entityName && <p>Entity: {metadata.entityName}</p>}
      {metadata.beanMethods?.length > 0 && <p>Bean methods: {metadata.beanMethods.join(", ")}</p>}
      {metadata.annotations?.length > 0 && <details><summary>Annotations</summary><ul>{metadata.annotations.map(a => <li key={a}>{a}</li>)}</ul></details>}
      {metadata.transport && <p>{metadata.transport}</p>}
    </> : <><p><span className="outline-badge">{edge.data.relationship.type}</span></p><h3>Source evidence</h3><SourceLocation key={edge.id} source={edge.data.relationship.evidence} /></>}
  </aside>;
}
function RelationshipList({ title, edges, byId, incoming, onSelect }) {
  return <section><h3>{title}</h3>{edges.length ? <ul className="arch-dependency-list">{edges.map(e => <li key={e.id}><button onClick={() => onSelect({ kind: "node", id: incoming ? e.source : e.target })}>{byId.get(incoming ? e.source : e.target)?.name}</button><button className="arch-relation-link" onClick={() => onSelect({ kind: "edge", id: e.id })} aria-label={`Inspect ${e.ariaLabel}`}>{incoming ? "←" : "→"} {e.data.relationship.type}</button></li>)}</ul> : <p className="muted">None in this snapshot.</p>}</section>;
}
