// Match the backend default; a deployment with a lower RAG limit can lower this too.
const configuredMax = Number(
  import.meta.env.VITE_AI_MAX_QUESTION_CHARS || 4000,
);
export const MAX_QUESTION_CHARS =
  Number.isInteger(configuredMax) && configuredMax > 0 && configuredMax <= 4000
    ? configuredMax
    : 4000;
export const MAX_QUERY_CHARS = 2000;
export function similarityLabel(value) {
  return typeof value === "number" &&
    Number.isFinite(value) &&
    value >= 0 &&
    value <= 1
    ? `${Math.round(value * 100)}%`
    : "Not available";
}
export function intelligenceError(error) {
  const status = error.response?.status,
    message = error.response?.data?.message;
  const configuration = [
    "Chat API key is not configured",
    "Chat provider configuration is invalid",
    "Structured chat workflow is not supported by this provider",
    "Embedding provider is not configured",
    "Embedding API key is not configured",
    "Embedding dimensions do not match database vector(1536)",
  ];
  if (configuration.includes(message))
    return "AI provider is not configured for this DevPilot instance.";
  if (error.code === "ECONNABORTED" || status === 504 || status === 408)
    return "The AI request timed out. Please try again.";
  if (status === 429)
    return "AI provider rate limit reached. Please try again later.";
  if (status === 404 || status === 403)
    return "Repository not found or not accessible to your account.";
  if (status === 401) return "Your session has expired. Please sign in again.";
  if (status === 409)
    return "Repository intelligence is not ready or its index changed. Check indexing status and try again.";
  if (status === 400)
    return "Please enter a valid question or query within the character limit.";
  if (message === "Embedding provider authentication failed")
    return "The AI provider rejected this instance’s credentials. Contact your administrator.";
  if (status === 502)
    return "The AI provider could not complete the request. Please try again or check the instance configuration.";
  if (status === 503)
    return "AI provider is temporarily unavailable. Please try again later.";
  return "Unable to complete the AI request. Check your connection and try again.";
}
