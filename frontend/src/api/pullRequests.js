import { api } from "./client";
export async function getPullRequests(id, page = 1, size = 20, signal) {
  const { data } = await api.get(`/api/repositories/${encodeURIComponent(id)}/pull-requests`, { params: { page, size }, signal });
  return data;
}
export async function getPullRequest(id, number, signal) {
  const { data } = await api.get(`/api/repositories/${encodeURIComponent(id)}/pull-requests/${encodeURIComponent(number)}`, { signal, timeout: 180000 });
  return data;
}
export async function reviewPullRequest(id, number, body, signal) {
  const { data } = await api.post(`/api/repositories/${encodeURIComponent(id)}/pull-requests/${encodeURIComponent(number)}/review`, body, { signal, timeout: 900000 });
  return data;
}
