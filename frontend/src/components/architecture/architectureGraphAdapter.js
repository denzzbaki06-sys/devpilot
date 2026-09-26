// Presentation only. IDs, types, directions and evidence come from the backend.
export const NODE_WIDTH = 248;
export const NODE_HEIGHT = 92;
export const typeColors = Object.freeze({ CONTROLLER: "#a78bfa", SERVICE: "#60a5fa", REPOSITORY: "#34d399", ENTITY: "#fbbf24", CONFIGURATION: "#c4b5fd", SECURITY: "#fb7185", COMPONENT: "#94a3b8", UTILITY: "#a3a3a3", ENTRY_POINT: "#f472b6", EXTERNAL_CLIENT: "#22d3ee", MODULE: "#818cf8", FUNCTION: "#a3e635", UNKNOWN: "#94a3b8" });
export function colorFor(type) { return typeColors[type] || typeColors.UNKNOWN; }
export function relationshipId(r) {
  const e = r.evidence;
  return `edge:${JSON.stringify([r.sourceComponentId, r.targetComponentId, r.type, e ? [e.path, e.startLine, e.endLine, e.symbol] : null])}`;
}
export function architectureGraphAdapter(response) {
  const components = new Map(); let omitted = 0;
  for (const c of response.components || []) {
    if (!c || typeof c.id !== "string" || !c.id) { omitted++; continue; }
    if (!components.has(c.id)) components.set(c.id, c);
  }
  const nodes = [...components.values()].sort((a, b) => a.id.localeCompare(b.id)).map(c => ({
    id: c.id, type: "architecture", position: { x: 0, y: 0 }, width: NODE_WIDTH, height: NODE_HEIGHT,
    data: { component: c }, ariaLabel: `${c.name} · ${c.type}`, focusable: true,
  }));
  const exactEdges = new Map();
  for (const r of response.relationships || []) {
    if (!r || !components.has(r.sourceComponentId) || !components.has(r.targetComponentId)) { omitted++; continue; }
    const id = relationshipId(r);
    if (exactEdges.has(id)) continue;
    exactEdges.set(id, { id, source: r.sourceComponentId, target: r.targetComponentId, type: "smoothstep", label: r.type,
      markerEnd: { type: "arrowclosed", color: "#8492aa", width: 18, height: 18 }, data: { relationship: r },
      ariaLabel: `${components.get(r.sourceComponentId).name} → ${r.type} → ${components.get(r.targetComponentId).name}`, focusable: true,
    });
  }
  return { nodes, edges: [...exactEdges.values()].sort((a, b) => a.id.localeCompare(b.id)), omitted };
}
export function filterGraph(graph, hiddenNodes = new Set(), hiddenEdges = new Set()) {
  const nodes = graph.nodes.filter(n => !hiddenNodes.has(n.data.component.type));
  const ids = new Set(nodes.map(n => n.id));
  return { nodes, edges: graph.edges.filter(e => ids.has(e.source) && ids.has(e.target) && !hiddenEdges.has(e.data.relationship.type)) };
}
export function searchComponents(nodes, query) {
  const q = query.trim().toLocaleLowerCase();
  return nodes.filter(n => [n.data.component.name, n.data.component.qualifiedName, n.data.component.path, n.data.component.symbol, n.data.component.type].some(value => String(value ?? "").toLocaleLowerCase().includes(q)));
}
export function highlightGraph(graph, selection) {
  const active = new Set(), incoming = new Set(), outgoing = new Set();
  if (selection?.kind === "node") {
    active.add(selection.id);
    for (const e of graph.edges) {
      if (e.source === selection.id) outgoing.add(e.target);
      if (e.target === selection.id) incoming.add(e.source);
    }
  } else if (selection?.kind === "edge") {
    const e = graph.edges.find(e => e.id === selection.id);
    if (e) { active.add(e.source); active.add(e.target); }
  }
  const related = new Set([...active, ...incoming, ...outgoing]);
  return {
    nodes: graph.nodes.map(n => ({ ...n, selected: selection?.kind === "node" && n.id === selection.id,
      className: related.size && !related.has(n.id) ? "arch-subdued" : incoming.has(n.id) && outgoing.has(n.id) ? "arch-both" : incoming.has(n.id) ? "arch-incoming" : outgoing.has(n.id) ? "arch-outgoing" : "",
    })),
    edges: graph.edges.map(e => {
      const selected = selection?.kind === "edge" && e.id === selection.id;
      const direction = selection?.kind === "node" ? e.source === selection.id ? "outgoing" : e.target === selection.id ? "incoming" : "" : "";
      const emphasized = selected || Boolean(direction), color = selected ? "#c4b5fd" : direction === "incoming" ? "#fbbf24" : direction === "outgoing" ? "#22d3ee" : "#8492aa";
      return { ...e, selected, className: related.size && !emphasized ? "arch-subdued" : "",
        style: { stroke: color, strokeWidth: emphasized ? 2.5 : 1.3, strokeDasharray: direction === "incoming" ? "6 3" : undefined },
        markerEnd: { ...e.markerEnd, color }, labelStyle: { fill: emphasized ? color : "#b1bdd1", fontSize: 10 },
        labelBgStyle: { fill: "#111722", fillOpacity: .96 }, labelBgPadding: [6, 4],
        ariaLabel: `${e.ariaLabel}${direction ? ` (${direction})` : ""}`,
      };
    }),
  };
}
