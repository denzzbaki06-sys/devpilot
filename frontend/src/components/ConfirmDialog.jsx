import { useEffect, useRef, useId } from "react";
export default function ConfirmDialog({
  title,
  children,
  onCancel,
  onConfirm,
  busy,
  confirmLabel,
}) {
  const ref = useRef(null),
    titleId = useId();
  useEffect(() => {
    const previous = document.activeElement;
    ref.current.showModal();
    return () => {
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className="confirm-dialog"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onCancel();
      }}
    >
      <h2 id={titleId}>{title}</h2>
      <div className="dialog-copy">{children}</div>
      <div className="dialog-actions">
        <button
          autoFocus
          className="secondary-button"
          onClick={onCancel}
          disabled={busy}
        >
          Cancel
        </button>
        <button className="danger-button" onClick={onConfirm} disabled={busy}>
          {busy ? "Please wait…" : confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
