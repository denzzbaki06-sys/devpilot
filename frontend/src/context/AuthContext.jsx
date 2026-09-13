import { createContext, useCallback, useEffect, useState } from "react";
import { api, auth, refresh, revokeSession, session } from "../api/client";
export const AuthContext = createContext(null);
export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const restoreSession = useCallback(async () => {
    setLoading(true);
    try {
      if (session.refresh()) {
        await refresh();
        const { data } = await api.get("/api/users/me");
        setUser(data);
      }
    } catch {
      session.clear();
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    const unsubscribe = session.subscribe(setUser);
    restoreSession();
    return unsubscribe;
  }, [restoreSession]);
  async function authenticate(path, values) {
    const { data } = await auth.post(`/api/auth/${path}`, values);
    session.set(data);
    try {
      const response = await api.get("/api/users/me");
      setUser(response.data);
    } catch (error) {
      session.clear();
      throw error;
    }
  }
  return (
    <AuthContext.Provider
      value={{
        user,
        authenticated: !!user,
        loading,
        restoreSession,
        login: (values) => authenticate("login", values),
        register: (values) => authenticate("register", values),
        logout: revokeSession,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}
