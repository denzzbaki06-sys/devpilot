export function ErrorMessage({ children }) {
  return children ? (
    <div className="error" role="alert">
      {children}
    </div>
  ) : null;
}
export function LoadingShell() {
  return (
    <div
      className="bootstrap"
      role="status"
      aria-label="Restoring your workspace"
    >
      <div className="skeleton side" />
      <div className="skeleton-content">
        <div className="skeleton line" />
        <div className="skeleton panel" />
        <p>Preparing your workspace…</p>
      </div>
    </div>
  );
}
