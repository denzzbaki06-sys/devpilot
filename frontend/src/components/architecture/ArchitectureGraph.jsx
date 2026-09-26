import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ReactFlow, ReactFlowProvider, Background, Controls, MiniMap, Handle, Position } from "@xyflow/react";
import { Search, RotateCcw, Maximize2, Copy, Boxes } from "lucide-react";
import { architectureGraphAdapter, colorFor, filterGraph, highlightGraph, searchComponents, NODE_WIDTH, NODE_HEIGHT } from "./architectureGraphAdapter";
import { requestArchitectureLayout } from "./layoutRequest";
import ArchitectureDetailPanel from "./ArchitectureDetailPanel";
import "@xyflow/react/dist/style.css";
import "./architectureGraph.css";

const ArchitectureNode = memo(function ArchitectureNode({ data }) {
  const c = data.component;
  return <div className="arch-node" style={{ "--node-color": colorFor(c.type) }}>
    <Handle type="target" position={Position.Top} isConnectable={false} />
    <span className="arch-node-type"><span aria-hidden="true" />{c.type}</span>
    <strong title={c.name}>{c.name}</strong><small title={c.path}>{c.path}</small>
    <Handle type="source" position={Position.Bottom} isConnectable={false} />
  </div>;
});
const nodeTypes = { architecture: ArchitectureNode };
const fitOptions = { padding: .16, maxZoom: 1, minZoom: .001 };
const EMPTY_SET = new Set();
const EMPTY_IDS = [];
export default function ArchitectureGraph({ data, ...interaction }) {
  return <ReactFlowProvider><GraphWorkspace key={`${data.repositoryId}:${data.indexCommitSha || "unknown"}`} data={data} {...interaction} /></ReactFlowProvider>;
}
export function GraphWorkspace({ data, onContextChange, navigation, highlightedIds = EMPTY_IDS }) {
  const graph = useMemo(() => architectureGraphAdapter(data), [data]);
  const [layout, setLayout] = useState(null), [failure, setFailure] = useState("");
  const [fitRevision, setFitRevision] = useState(0);
  const [query, setQuery] = useState(""), [browse, setBrowse] = useState(false), [resultLimit, setResultLimit] = useState(50);
  const [hiddenNodes, setHiddenNodes] = useState(EMPTY_SET), [hiddenEdges, setHiddenEdges] = useState(EMPTY_SET);
  const [selection, setSelection] = useState(null), [instance, setInstance] = useState(null), [copyStatus, setCopyStatus] = useState("");
  const searchRef = useRef(null), canvasRef = useRef(null);
  useEffect(() => {
    setFailure(""); setSelection(null);
    if (!graph.nodes.length) return;
    return requestArchitectureLayout(graph, positions => setLayout({ graph, positions: new Map(positions.map(n => [n.id, n.position])) }), setFailure);
  }, [graph]);
  const ready = layout?.graph === graph;
  const positioned = useMemo(() => ({ ...graph, nodes: ready ? graph.nodes.map(n => ({ ...n, position: layout.positions.get(n.id) || n.position })) : [] }), [graph, ready, layout]);
  const visible = useMemo(() => filterGraph(positioned, hiddenNodes, hiddenEdges), [positioned, hiddenNodes, hiddenEdges]);
  const highlighted = useMemo(() => highlightGraph(visible, selection), [visible, selection]);
  const answerHighlighted = useMemo(() => ({ ...highlighted, nodes: highlighted.nodes.map(n => highlightedIds.includes(n.id) ? { ...n, className: `${n.className} arch-answer-reference`, ariaLabel: `${n.ariaLabel} · Referenced by answer` } : n) }), [highlighted, highlightedIds]);
  const results = useMemo(() => searchComponents(graph.nodes.filter(n => !hiddenNodes.has(n.data.component.type)), query), [graph, hiddenNodes, query]);
  const types = useMemo(() => [...new Set(graph.nodes.map(n => n.data.component.type))].sort(), [graph]);
  const edgeTypes = useMemo(() => [...new Set(graph.edges.map(e => e.data.relationship.type))].sort(), [graph]);
  const entryIds = useMemo(() => new Set((data.entryPoints || []).map(c => c.id)), [data.entryPoints]);
  const fit = useCallback(() => instance?.fitView({ ...fitOptions, duration: 200 }), [instance]);
  useEffect(() => { if (ready && instance) instance.fitView(fitOptions); }, [ready, instance]);
  useEffect(() => {
    if (!fitRevision || !ready || !instance) return;
    const frame = requestAnimationFrame(() => instance.fitView({ ...fitOptions, duration: 200 }));
    return () => cancelAnimationFrame(frame);
  }, [fitRevision, ready, instance]);
  useEffect(() => {
    if (!ready || !instance || !selection) return;
    const nodeId = selection.kind === "node" ? selection.id : graph.edges.find(e => e.id === selection.id)?.source;
    const point = layout.positions.get(nodeId);
    if (point) instance.setCenter(point.x + NODE_WIDTH / 2, point.y + NODE_HEIGHT / 2, { zoom: .9, duration: 220 });
  }, [selection, ready, instance, graph, layout]);
  const close = useCallback(() => { setSelection(null); onContextChange?.(null); searchRef.current?.focus(); }, [onContextChange]);
  const select = useCallback((item, updateContext = true) => {
    if (item.kind === "node") {
      const n = graph.nodes.find(n => n.id === item.id); if (!n) return;
      setHiddenNodes(previous => { if (!previous.has(n.data.component.type)) return previous; const next = new Set(previous); next.delete(n.data.component.type); return next; });
    } else {
      const edge = graph.edges.find(e => e.id === item.id); if (!edge) return;
      const required = graph.nodes.filter(n => n.id === edge.source || n.id === edge.target).map(n => n.data.component.type);
      setHiddenNodes(previous => new Set([...previous].filter(t => !required.includes(t))));
      setHiddenEdges(previous => new Set([...previous].filter(t => t !== edge.data.relationship.type)));
    }
    if (updateContext) onContextChange?.(item.kind === "node" ? item.id : null);
    setSelection(previous => previous?.id === item.id && previous?.kind === item.kind ? previous : item);
  }, [graph, onContextChange]);
  useEffect(() => { if (navigation) select(navigation, false); }, [navigation, select]);
  useEffect(() => {
    if (!highlightedIds.length || !ready || !instance) return;
    const referenced = graph.nodes.filter(n => highlightedIds.includes(n.id));
    if (!referenced.length) return;
    const types = new Set(referenced.map(n => n.data.component.type));
    setHiddenNodes(previous => { const next = new Set([...previous].filter(t => !types.has(t))); return next.size === previous.size ? previous : next; });
    canvasRef.current?.focus();
    const frame = requestAnimationFrame(() => instance.fitView({ ...fitOptions, nodes: referenced, duration: 200 }));
    return () => cancelAnimationFrame(frame);
  }, [highlightedIds, ready, instance, graph]);
  const onNodeClick = useCallback((_, node) => select({ kind: "node", id: node.id }), [select]);
  const onEdgeClick = useCallback((_, edge) => select({ kind: "edge", id: edge.id }), [select]);
  const onNodesChange = useCallback(changes => { const selected = changes.find(c => c.type === "select" && c.selected); if (selected) select({ kind: "node", id: selected.id }); }, [select]);
  const onEdgesChange = useCallback(changes => { const selected = changes.find(c => c.type === "select" && c.selected); if (selected) select({ kind: "edge", id: selected.id }); }, [select]);
  function toggle(type, setter) { setter(previous => { const next = new Set(previous); if (next.has(type)) next.delete(type); else next.add(type); return next; }); setSelection(null); }
  function reset() { setHiddenNodes(EMPTY_SET); setHiddenEdges(EMPTY_SET); setQuery(""); setBrowse(false); setResultLimit(50); setSelection(null); onContextChange?.(null); setFitRevision(n => n + 1); }
  async function copySha() { try { await navigator.clipboard.writeText(data.indexCommitSha); setCopyStatus("Snapshot SHA copied."); } catch { setCopyStatus("Copy unavailable. The full SHA is available in snapshot details."); } }
  return <div className="arch-map-workspace" onKeyDown={e => { if (e.key === "Escape") close(); }}>
    <div className="arch-map-summary"><div><span className="eyebrow">SOURCE-BACKED MAP</span><p>{graph.nodes.length} components <span>·</span> {graph.edges.length} relationships <span>·</span> {data.entryPoints?.length || 0} entry points</p></div><div className="arch-snapshot"><span>Analyzed snapshot</span>{data.indexCommitSha ? <><button onClick={copySha} title={data.indexCommitSha} aria-label="Copy snapshot SHA"><code>{data.indexCommitSha.slice(0, 10)}</code><Copy size={13} /></button><details><summary>Full SHA</summary><code>{data.indexCommitSha}</code></details></> : <span>Unknown</span>}<span role="status">{copyStatus}</span></div></div>
    {(data.warnings?.length > 0 || graph.omitted > 0) && <aside className="arch-map-warnings" aria-label="Analysis warnings"><details open><summary>Analysis scope</summary><ul>{data.warnings?.map(w => <li key={w}>{w}</li>)}{graph.omitted > 0 && <li>{graph.omitted} invalid graph records could not be displayed. No replacement nodes were created.</li>}</ul></details></aside>}
    {!graph.nodes.length ? <section className="panel empty-state"><Boxes size={30} /><h2>No supported architecture components were detected for this repository.</h2></section> : <>
      <div className="arch-toolbar">
        <label className="arch-search"><Search size={17} aria-hidden="true" /><span className="sr-only">Search architecture</span><input ref={searchRef} value={query} onChange={e => { setQuery(e.target.value); setResultLimit(50); }} placeholder="Search architecture..." /></label>
        <button className="secondary-button" onClick={() => setBrowse(v => !v)} aria-expanded={browse}>Browse components</button>
        <details className="arch-filters"><summary>Filters</summary><fieldset><legend>Component types</legend>{types.map(type => <label key={type}><input type="checkbox" checked={!hiddenNodes.has(type)} onChange={() => toggle(type, setHiddenNodes)} />{type}</label>)}</fieldset><fieldset><legend>Relationship types</legend>{edgeTypes.map(type => <label key={type}><input type="checkbox" checked={!hiddenEdges.has(type)} onChange={() => toggle(type, setHiddenEdges)} />{type}</label>)}</fieldset></details>
        <button className="secondary-button" onClick={fit} disabled={!ready || !visible.nodes.length}><Maximize2 size={15} />Fit View</button>
        <button className="secondary-button" onClick={reset}><RotateCcw size={15} />Reset view</button>
      </div>
      {(query.trim() || browse) && <section className="arch-search-results" aria-label="Architecture search results"><p role="status">{results.length} matching components in the current filters.</p>{results.length ? <ul>{results.slice(0, resultLimit).map(n => <li key={n.id}><button aria-label={`${n.data.component.name} ${n.data.component.type} ${n.data.component.path}`} onClick={() => select({ kind: "node", id: n.id })}><strong>{n.data.component.name}</strong><span>{n.data.component.type}</span><small>{n.data.component.path}</small></button></li>)}</ul> : <p>No matching components. Try another search or reset filters.</p>}{results.length > resultLimit && <button className="secondary-button" onClick={() => setResultLimit(n => n + 50)}>Show more components</button>}</section>}
      <div className={`arch-map-and-detail ${selection ? "has-detail" : ""}`}>
        <div ref={canvasRef} tabIndex={-1} className="arch-canvas" aria-label="Architecture graph">
          {failure ? <div className="arch-canvas-message" role="alert">{failure}</div> : !ready ? <div className="arch-canvas-message" role="status">Arranging architecture map…</div> : !visible.nodes.length ? <div className="arch-canvas-message">No components match these filters. Use Reset view to restore the graph.</div> : <ReactFlow
            nodes={answerHighlighted.nodes} edges={answerHighlighted.edges} nodeTypes={nodeTypes} onInit={setInstance} onNodeClick={onNodeClick} onEdgeClick={onEdgeClick}
            onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onPaneClick={close} nodesDraggable={false} nodesConnectable={false} edgesReconnectable={false}
            deleteKeyCode={null} multiSelectionKeyCode={null} nodesFocusable edgesFocusable disableKeyboardA11y={false} onlyRenderVisibleElements
            minZoom={.001} maxZoom={1.6} fitView fitViewOptions={fitOptions} zoomOnScroll zoomOnPinch panOnDrag preventScrolling={false} colorMode="dark"
          ><Background color="#263144" gap={24} size={1} /><Controls showInteractive={false} fitViewOptions={fitOptions} />{visible.nodes.length >= 40 && <MiniMap pannable zoomable nodeColor={n => colorFor(n.data.component.type)} ariaLabel="Architecture overview minimap" />}</ReactFlow>}
        </div>
        {selection && <ArchitectureDetailPanel selection={selection} graph={graph} entryIds={entryIds} onSelect={select} onClose={close} />}
      </div>
      <div className="arch-map-footer"><p>{ready ? `${visible.nodes.length} / ${graph.nodes.length} components · ${visible.edges.length} / ${graph.edges.length} relationships visible` : "Preparing layout"}</p><span>Arrows follow source → target. Incoming: dashed amber · Outgoing: solid cyan.</span><details><summary>Node legend</summary><ul>{types.map(type => <li key={type}><span style={{ background: colorFor(type) }} aria-hidden="true" />{type}</li>)}</ul></details></div>
    </>}
  </div>;
}
