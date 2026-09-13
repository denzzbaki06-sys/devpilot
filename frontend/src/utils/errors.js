export function errorMessage(error) {
  const status = error.response?.status;
  const messages = {
    400: "Please check your details and try again.",
    401: "Your credentials or session are no longer valid. Please sign in.",
    403: "You do not have permission to perform this action.",
    409: "This account or resource already exists.",
    429: "Too many requests. Please try again shortly.",
    503: "This service is not configured yet.",
  };
  return (
    messages[status] ||
    (status
      ? "We could not complete this request. Please try again."
      : "Unable to reach DevPilot. Check your connection and try again.")
  );
}
