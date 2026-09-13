import { api } from "./client";
// AI requests can outlast the ordinary 20-second API timeout (embedding + chat).
const requestOptions = (signal) => ({ signal, timeout: 180000 });
export const semanticSearch = (repositoryId, query, limit = 8, signal) =>
  api
    .post(
      `/api/repositories/${encodeURIComponent(repositoryId)}/search`,
      { query, limit },
      requestOptions(signal),
    )
    .then((r) => r.data);
export const askRepository = (repositoryId, question, topK, signal) =>
  api
    .post(
      `/api/repositories/${encodeURIComponent(repositoryId)}/ask`,
      { question, ...(topK == null ? {} : { topK }) },
      requestOptions(signal),
    )
    .then((r) => r.data);
