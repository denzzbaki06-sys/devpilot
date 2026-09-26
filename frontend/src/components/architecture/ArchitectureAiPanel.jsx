import { useEffect, useRef, useState } from "react";
import { askArchitecture } from "../../api/architecture";
import { intelligenceError } from "../../utils/intelligence";
import AnswerText from "../intelligence/AnswerText";
import { SourceLocation } from "./ArchitectureDetailPanel";
import { relationshipId } from "./architectureGraphAdapter";
import "./architectureAi.css";

export const EXPLAIN_COMPONENT = "Explain the role of this component in the repository architecture.";
export const EXPLAIN_CONNECTIONS = "Explain how this component interacts with its connected components.";
function errorMessage(error) {
  const status = error.response?.status;
  if (status === 409) return "The repository index or architecture changed. Reload architecture before asking again.";
  if (status === 400) return "Check your question and selected component. Do not include credentials.";
  return intelligenceError(error);
}
export default function ArchitectureAiPanel({ data, selectedComponentId, onClearContext, onNavigate, onHighlight, highlightedIds = [] }) {
  const [question, setQuestion] = useState(""), [busy, setBusy] = useState(false), [answer, setAnswer] = useState(null), [error, setError] = useState("");
  const [source, setSource] = useState(null);
  const current = useRef(null), generation = useRef(0), input = useRef(null), sourceBox = useRef(null);
  useEffect(() => { if (source) sourceBox.current?.focus(); }, [source]);
  const selected = data.components.find(c => c.id === selectedComponentId);
  useEffect(() => {
    generation.current++; current.current?.abort(); current.current = null;
    setBusy(false); setAnswer(null); setError(""); setSource(null); onHighlight?.([]);
    const counter = generation;
    return () => { counter.current++; current.current?.abort(); current.current = null; };
  }, [data.repositoryId, data.indexCommitSha, selectedComponentId, onHighlight]);
  async function submit(text) {
    if (current.current || !text.trim() || text.length > 4000 || !data.components.length) return;
    const controller = new AbortController(), requestGeneration = ++generation.current;
    current.current = controller; setBusy(true); setError(""); setAnswer(null); setSource(null); onHighlight?.([]);
    try {
      const result = await askArchitecture(data.repositoryId, { question: text.trim(), ...(selected ? { selectedComponentId: selected.id } : {}), indexCommitSha: data.indexCommitSha }, controller.signal);
      if (controller.signal.aborted || generation.current !== requestGeneration) return;
      if (String(result.repositoryId) !== String(data.repositoryId) || result.indexCommitSha !== data.indexCommitSha) throw { response: { status: 409 } };
      if (typeof result.answer !== "string" || !Array.isArray(result.sources) || !Array.isArray(result.components) || !Array.isArray(result.relationships)) throw new Error("Invalid architecture answer");
      setAnswer(result);
    } catch (e) { if (!controller.signal.aborted && generation.current === requestGeneration) setError(errorMessage(e)); }
    finally { if (generation.current === requestGeneration) { current.current = null; setBusy(false); } }
  }
  function cancel() { generation.current++; current.current?.abort(); current.current = null; setBusy(false); }
  const actualIds = new Set(data.components.map(c => c.id));
  const components = (answer?.components || []).filter(c => actualIds.has(c.id));
  const actualEdges = new Set(data.relationships.map(relationshipId));
  const relationships = (answer?.relationships || []).filter(e => actualEdges.has(relationshipId(e)));
  const refs = Object.fromEntries([...(answer?.sources || []).map(s => [s.id, { kind: "source", value: s }]), ...components.map(c => [c.ref, { kind: "node", value: c }]), ...relationships.map(e => [e.ref, { kind: "edge", value: e }])]);
  function citation(ref) {
    const hit = refs[ref]; if (!hit) return;
    if (hit.kind === "source") setSource(hit.value);
    else onNavigate?.({ kind: hit.kind, id: hit.kind === "node" ? hit.value.id : relationshipId(hit.value) });
  }
  return <section className="panel architecture-ai" aria-label="AI Architecture Intelligence">
    <div className="architecture-ai-heading"><div><span className="eyebrow">ARCHITECTURE INTELLIGENCE</span><h2>Ask about this architecture</h2></div><span className="outline-badge">Single question · source grounded</span></div>
    {!data.components.length ? <p>Architecture analysis did not detect supported components for this repository.</p> : <>
      <p className="architecture-ai-context">Context: <strong>{selected?.name || "Repository architecture"}</strong>{selected && <button className="secondary-button" onClick={onClearContext}>Clear context</button>}</p>
      {data.warnings?.length > 0 && <p className="muted">Architecture scope is limited by the analysis warnings shown above. Explanations use bounded evidence.</p>}
      {selected && <div className="architecture-ai-actions"><button className="secondary-button" disabled={busy} onClick={() => { setQuestion(EXPLAIN_COMPONENT); submit(EXPLAIN_COMPONENT); }}>Explain Component</button><button className="secondary-button" disabled={busy} onClick={() => { setQuestion(EXPLAIN_CONNECTIONS); submit(EXPLAIN_CONNECTIONS); }}>Explain Connections</button></div>}
      <form onSubmit={e => { e.preventDefault(); submit(question); }}>
        <label htmlFor="architecture-question">Architecture question</label>
        <textarea id="architecture-question" ref={input} value={question} maxLength={4000} rows={3} placeholder="Ask about this architecture..." onChange={e => setQuestion(e.target.value)} onKeyDown={e => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(question); } }} />
        <div className="architecture-ai-actions"><button className="primary inline" disabled={busy || !question.trim()} type="submit">{busy ? "Analyzing repository architecture..." : "Ask architecture"}</button>{busy && <button className="secondary-button" type="button" onClick={cancel}>Cancel analysis</button>}<small className="muted">{question.length}/4000 · Ctrl/⌘ + Enter</small></div>
      </form>
      {busy && <p role="status">Analyzing repository architecture...</p>}
      {error && <p role="alert">{error}</p>}
      {answer && <div className="architecture-ai-answer" aria-label="Architecture answer">
        <AnswerText answer={answer.answer} sources={[]} references={refs} onCitation={citation} />
        {!answer.grounded && <p className="muted">Insufficient repository evidence.</p>}
        {answer.warnings?.length > 0 && <details><summary>Answer scope</summary><ul>{answer.warnings.map((w,i) => <li key={i}>{w}</li>)}</ul></details>}
        {components.length > 0 && <div className="architecture-ai-actions"><button className="secondary-button" onClick={() => onHighlight?.(components.map(c => c.id))}>Show in graph</button>{highlightedIds.length > 0 && <button className="secondary-button" onClick={() => onHighlight?.([])}>Clear highlight</button>}</div>}
        {source && <div ref={sourceBox} className="architecture-ai-source" tabIndex={-1}><h3>Source {source.id}</h3><SourceLocation key={source.id} source={source} /></div>}
      </div>}
    </>}
  </section>;
}
