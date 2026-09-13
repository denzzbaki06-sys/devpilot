import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { LoadingShell } from "../components/Feedback";
export function Protected() {
  const { loading, authenticated } = useAuth();
  const location = useLocation();
  return loading ? (
    <LoadingShell />
  ) : authenticated ? (
    <Outlet />
  ) : (
    <Navigate
      to="/login"
      state={{ from: location.pathname + location.search }}
      replace
    />
  );
}
export function PublicOnly() {
  const { loading, authenticated } = useAuth();
  return loading ? (
    <LoadingShell />
  ) : authenticated ? (
    <Navigate to="/dashboard" replace />
  ) : (
    <Outlet />
  );
}
