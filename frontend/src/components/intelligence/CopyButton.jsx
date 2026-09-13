import { useEffect, useState } from "react";
import { Copy, Check } from "lucide-react";
export default function CopyButton({ text, label }) {
  const [state, setState] = useState("idle");
  useEffect(() => {
    setState("idle");
  }, [text]);
  useEffect(() => {
    if (state === "idle") return;
    const timer = setTimeout(() => setState("idle"), 2500);
    return () => clearTimeout(timer);
  }, [state]);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setState("copied");
    } catch {
      setState("failed");
    }
  }
  return (
    <div className="copy-control">
      <button
        className="copy-button"
        onClick={copy}
        type="button"
        aria-label={label}
      >
        {state === "copied" ? <Check size={14} /> : <Copy size={14} />}{" "}
        {state === "copied" ? "Copied" : label}
      </button>
      <span className="sr-only" role="status">
        {state === "copied"
          ? "Copied to clipboard"
          : state === "failed"
            ? "Copy unavailable. Select the text and copy manually."
            : ""}
      </span>
      {state === "failed" && (
        <small>Copy unavailable; select the text to copy.</small>
      )}
    </div>
  );
}
