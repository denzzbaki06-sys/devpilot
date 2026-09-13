import { api } from "./client";
export const getGitHubStatus = (signal) =>
  api.get("/api/github/status", { signal }).then((r) => r.data);
export const startGitHubConnect = () =>
  api.get("/api/github/connect", { withCredentials: true }).then((r) => r.data);
export const getGitHubRepositories = (signal) =>
  api.get("/api/github/repositories", { signal }).then((r) => r.data);
export const disconnectGitHub = () =>
  api.post("/api/github/disconnect", null, { withCredentials: true });
