import { architectureLayout } from "./architectureLayout";
self.onmessage = ({ data }) => {
  try { self.postMessage({ positions: architectureLayout(data.nodes, data.edges) }); }
  catch { self.postMessage({ error: "Architecture layout is unavailable. Use Components view to explore this snapshot." }); }
};
