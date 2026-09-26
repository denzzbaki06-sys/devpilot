import { api } from "./client";
export async function getArchitecture(id, signal) {
  const { data } = await api.get(`/api/repositories/${encodeURIComponent(id)}/architecture`, { signal, timeout: 60000 });
  return data;
}

export async function askArchitecture(id, request, signal) {
  const { data } = await api.post(`/api/repositories/${encodeURIComponent(id)}/architecture/ask`, request, { signal, timeout: 420000 });
  return data;
}
