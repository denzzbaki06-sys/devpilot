import { createSession } from "./session";
import { createApi } from "./createClient";
const baseURL = import.meta.env.VITE_API_BASE_URL;
if (!baseURL) throw new Error("Set VITE_API_BASE_URL in frontend/.env");
let storage;
try {
  storage = window.sessionStorage;
} catch {
  /* Memory-only session. */
}
export const session = createSession(storage);
export const {
  api,
  auth,
  refresh,
  logout: revokeSession,
} = createApi(baseURL, session);
