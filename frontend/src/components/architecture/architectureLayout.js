import dagre from "@dagrejs/dagre";
import { NODE_WIDTH, NODE_HEIGHT } from "./architectureGraphAdapter";

// Dagre places each connected island; stable shelf packing prevents disconnected
// components becoming a single extremely wide row. This changes coordinates only.
export function architectureLayout(nodes, edges) {
  if (!nodes.length) return [];
  const graph = new dagre.graphlib.Graph();
  const sorted = [...nodes].sort((a, b) => a.id.localeCompare(b.id));
  sorted.forEach(n => graph.setNode(n.id)); edges.forEach(e => graph.setEdge(e.source, e.target));
  const islands = dagre.graphlib.alg.components(graph).map(ids => ids.sort()).sort((a, b) => a[0].localeCompare(b[0]));
  const groups = islands.map(ids => {
    const set = new Set(ids), g = new dagre.graphlib.Graph({ multigraph: true });
    g.setGraph({ rankdir: "TB", ranker: "longest-path", nodesep: 46, ranksep: 90, marginx: 24, marginy: 24 });
    g.setDefaultEdgeLabel(() => ({}));
    ids.forEach(id => g.setNode(id, { width: NODE_WIDTH, height: NODE_HEIGHT }));
    edges.filter(e => set.has(e.source) && set.has(e.target)).sort((a, b) => a.id.localeCompare(b.id)).forEach(e => g.setEdge(e.source, e.target, { weight: 1 }, e.id));
    dagre.layout(g);
    return { graph: g, ids, width: g.graph().width, height: g.graph().height };
  });
  const shelfWidth = Math.max(...groups.map(g => g.width), Math.sqrt(groups.reduce((sum, g) => sum + (g.width + 50) * (g.height + 50), 0)) * 1.3);
  let x = 0, y = 0, rowHeight = 0;
  const positions = new Map();
  for (const group of groups) {
    if (x > 0 && x + group.width > shelfWidth) { x = 0; y += rowHeight + 50; rowHeight = 0; }
    for (const id of group.ids) {
      const point = group.graph.node(id);
      if (!Number.isFinite(point?.x) || !Number.isFinite(point?.y)) throw new Error("Layout unavailable");
      positions.set(id, { x: x + point.x - NODE_WIDTH / 2, y: y + point.y - NODE_HEIGHT / 2 });
    }
    x += group.width + 50; rowHeight = Math.max(rowHeight, group.height);
  }
  return sorted.map(n => ({ id: n.id, position: positions.get(n.id) }));
}
