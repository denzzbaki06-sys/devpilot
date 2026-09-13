import { errorMessage } from "./errors";
export function repositoryError(error) {
  const message = error.response?.data?.message;
  const known = {
    "Connect GitHub first": "Connect GitHub first to access your repositories.",
    "GitHub OAuth is not configured":
      "GitHub connection is not configured on this server yet.",
    "GitHub authorization expired or revoked; reconnect GitHub":
      "Your GitHub authorization has expired. Reconnect GitHub to continue.",
    "GitHub access forbidden or rate limited":
      "GitHub denied access. Check repository permissions or try again later.",
    "GitHub rate limit reached; retry later":
      "GitHub rate limit reached. Please try again later.",
    "Repository already connected":
      "This repository is already in your workspace.",
    "Connected repository not found":
      "Repository not found or not accessible to your account.",
    "GitHub repository not found or inaccessible":
      "This repository is no longer accessible on GitHub.",
    "Repository indexing is already running":
      "Indexing is already running. Status has been refreshed.",
    "Cannot delete repository while indexing":
      "Wait for indexing to finish before removing this repository.",
    "Indexing capacity reached; retry later":
      "Indexing is busy. Please try again later.",
  };
  return (
    known[message] ||
    (error.response?.status === 404
      ? "Repository not found or not accessible to your account."
      : errorMessage(error))
  );
}
export function dateLabel(value) {
  if (!value) return "Not available";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "Not available"
    : date.toLocaleDateString(undefined, {
        year: "numeric",
        month: "short",
        day: "numeric",
      });
}
export function oauthNotice(search) {
  const params = new URLSearchParams(search);
  if (params.has("error"))
    return {
      type: "error",
      text: "GitHub connection was not completed. Please try connecting again.",
    };
  if (params.get("connected") === "true")
    return {
      type: "success",
      text: "GitHub authorization completed. Checking your connection…",
    };
  return null;
}
export function authorizationDestination(value) {
  const url = new URL(value);
  if (
    url.origin !== "https://github.com" ||
    url.pathname !== "/login/oauth/authorize" ||
    url.username ||
    url.password
  )
    throw new Error("Invalid OAuth destination");
  return url.href;
}
export function workspaceSummary(repositories) {
  const ready = repositories.filter((r) => r.status === "READY");
  return {
    connected: repositories.length,
    ready: ready.length,
    ai: ready.length ? "Ready to ask" : "Index a repository first",
  };
}
