// Backend contract: plain text with server-appended [1] references into sources order.
// No HTML interpretation, no external links, no invented source IDs.
export default function AnswerText({ answer, sources, onCitation }) {
  function prose(text, key) {
    return (
      <div className="answer-prose" key={key}>
        {text.split(/(\[\d+\])/g).map((part, index) => {
          const match = /^\[([1-9]\d*)\]$/.exec(part);
          const citation = match ? Number(match[1]) : 0;
          return citation > 0 && citation <= sources.length ? (
            <button
              key={index}
              className="citation-chip"
              onClick={() => onCitation(citation)}
              aria-label={`Go to source ${citation}`}
            >
              {part}
            </button>
          ) : (
            part
          );
        })}
      </div>
    );
  }
  const parts = [],
    fences = /^```[^\n]*\n([\s\S]*?)^```[ \t]*$/gm;
  let cursor = 0,
    match;
  while ((match = fences.exec(answer)) !== null) {
    if (match.index > cursor)
      parts.push(prose(answer.slice(cursor, match.index), cursor));
    parts.push(
      <pre
        className="answer-code"
        tabIndex={0}
        aria-label="Answer code block"
        key={`code-${match.index}`}
      >
        <code>{match[1]}</code>
      </pre>,
    );
    cursor = fences.lastIndex;
  }
  if (cursor < answer.length) parts.push(prose(answer.slice(cursor), cursor));
  return <div className="answer-text">{parts}</div>;
}
