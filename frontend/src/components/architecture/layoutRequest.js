// Worker keeps larger layouts off the UI thread; termination prevents stale work.
export function requestArchitectureLayout(graph, onResult, onError) {
  let worker, timer, active = true;
  const stop = () => { active = false; clearTimeout(timer); worker?.terminate(); };
  try {
    worker = new Worker(new URL("./architectureLayout.worker.js", import.meta.url), { type: "module" });
    worker.onmessage = ({ data }) => { if (!active) return; stop(); if (data.error) onError(data.error); else onResult(data.positions); };
    worker.onerror = () => { if (!active) return; stop(); onError("Architecture layout is unavailable. Use Components view to explore this snapshot."); };
    worker.postMessage({ nodes: graph.nodes.map(n => ({ id: n.id })), edges: graph.edges.map(e => ({ id: e.id, source: e.source, target: e.target })) });
    timer = setTimeout(() => { stop(); onError("Architecture layout exceeded its time budget. Use Components view to explore this snapshot."); }, 15000);
  } catch { stop(); onError("Architecture layout is unavailable. Use Components view to explore this snapshot."); }
  return stop;
}
