import axios from "axios";
export function createApi(baseURL, session, adapter) {
  const options = { baseURL, timeout: 20000, ...(adapter ? { adapter } : {}) };
  const api = axios.create(options);
  const auth = axios.create(options); // No refresh interceptor on authentication requests.
  let flight = null;
  async function refresh() {
    if (flight) return flight;
    const token = session.refresh();
    if (!token) throw new Error("No session");
    const revision = session.revision();
    flight = auth
      .post("/api/auth/refresh", { refreshToken: token })
      .then(({ data }) => {
        if (revision !== session.revision()) throw new Error("Session changed");
        session.set(data);
        return data;
      })
      .catch((error) => {
        if (revision === session.revision()) session.clear();
        throw error;
      })
      .finally(() => {
        flight = null;
      });
    return flight;
  }
  api.interceptors.request.use((config) => {
    config.sessionRevision = session.revision();
    config.sentAccess = session.access();
    if (config.sentAccess)
      config.headers.set("Authorization", `Bearer ${config.sentAccess}`);
    else config.headers.delete("Authorization");
    return config;
  });
  api.interceptors.response.use(
    (response) => response,
    async (error) => {
      const config = error.config;
      if (error.response?.status !== 401 || !config) throw error;
      // Upstream GitHub authorization is independent of the DevPilot JWT session.
      if (
        error.response?.data?.message ===
        "GitHub authorization expired or revoked; reconnect GitHub"
      )
        throw error;
      if (config.retried) {
        session.clear();
        throw error;
      }
      config.retried = true;
      // A late 401 for the old token can reuse the already rotated access token.
      if (session.access() && config.sentAccess !== session.access())
        return api(config);
      if (!session.refresh()) {
        session.clear();
        throw error;
      }
      await refresh();
      return api(config);
    },
  );
  async function logout() {
    // Revoke the latest rotated refresh token if a refresh is already in flight.
    if (flight) {
      try {
        await flight;
      } catch {
        /* Local logout still completes. */
      }
    }
    const token = session.refresh();
    session.clear();
    if (token) await auth.post("/api/auth/logout", { refreshToken: token });
  }
  return { api, auth, refresh, logout };
}
