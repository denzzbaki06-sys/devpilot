import { FileCode2 } from "lucide-react";
import { similarityLabel } from "../../utils/intelligence";
export default function SourceCard({ source, number, sourceRef }) {
  return (
    <article
      className="source-card"
      tabIndex={-1}
      ref={sourceRef}
      aria-label={`Source ${number}: ${source.path}`}
    >
      <div className="source-card-heading">
        <span className="source-number">[{number}]</span>
        <FileCode2 size={15} />
        <strong>{source.path.split("/").pop()}</strong>
      </div>
      <p className="source-path">{source.path}</p>
      <div className="source-line">
        Lines {source.startLine}–{source.endLine}
      </div>
      {(source.symbolName || source.symbolType) && (
        <p className="source-symbol">
          {[source.symbolName, source.symbolType].filter(Boolean).join(" · ")}
        </p>
      )}
      <div className="source-footer">
        <span>{source.language || "Language not specified"}</span>
        {source.similarity != null && (
          <span>Similarity {similarityLabel(source.similarity)}</span>
        )}
      </div>
      <details>
        <summary>Source metadata</summary>
        <p>
          Chunk {source.chunkId}. This answer provides source references; code
          content is not included.
        </p>
      </details>
    </article>
  );
}
