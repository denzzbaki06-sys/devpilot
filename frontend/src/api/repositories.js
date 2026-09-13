import { api } from "./client";
export const getConnectedRepositories = (signal) =>
  api.get("/api/repositories", { signal }).then((r) => r.data);
export const getRepository = (id, signal) =>
  api
    .get(`/api/repositories/${encodeURIComponent(id)}`, { signal })
    .then((r) => r.data);
export const connectRepository = (githubRepositoryId) =>
  api.post("/api/repositories", { githubRepositoryId }).then((r) => r.data);
export const removeRepository = (id) =>
  api.delete(`/api/repositories/${encodeURIComponent(id)}`);
export const startIndexing = (id) =>
  api
    .post(`/api/repositories/${encodeURIComponent(id)}/index`)
    .then((r) => r.data);
export const getIndexStatus = (id, signal) =>
  api
    .get(`/api/repositories/${encodeURIComponent(id)}/index-status`, { signal })
    .then((r) => r.data);
