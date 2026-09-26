import { repositoryError } from "./repositoryUx";
export function pullRequestError(error) {
  const messages = {
    "Pull request retrieval timed out; retry": "GitHub took too long to return this diff. Please retry.",
    "GitHub repository or pull request not found or inaccessible": "Pull request or repository not found, or no longer accessible on GitHub.",
    "GitHub returned an invalid pull request response": "GitHub returned an incomplete response. Please try again.",
    "Pull request changed during retrieval; retry": "This pull request changed while loading. Refresh to get a consistent diff.",
    "Pull request exceeds ingestion limits; no partial context returned": "This pull request is too large to load safely. View it on GitHub.",
    "Invalid pull request number": "Invalid pull request number.",
    "Invalid pull request pagination": "Invalid pull request page.",
  };
  return messages[error.response?.data?.message] || repositoryError(error);
}
export function safeGitHubLink(value) {
  try {
    const url = new URL(value);
    return url.origin === "https://github.com" && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}
