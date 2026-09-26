import ArchitecturePage from "./pages/ArchitecturePage";
import { createRoot } from "react-dom/client";
import { BrowserRouter, Routes, Route, Navigate, Link } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import { Protected, PublicOnly } from "./routes/Guards";
import AuthPage from "./pages/AuthPage";
import WorkspacePage from "./pages/WorkspacePage";
import AppShell from "./layouts/AppShell";
import GitHubPage from "./pages/GitHubPage";
import RepositoriesPage from "./pages/RepositoriesPage";
import RepositoryDetailPage from "./pages/RepositoryDetailPage";
import PullRequestsPage from "./pages/PullRequestsPage";
import IntelligencePage from "./pages/IntelligencePage";
import RepositoryPicker from "./components/intelligence/RepositoryPicker";
import "./styles/global.css";
createRoot(document.getElementById("root")).render(
  <BrowserRouter>
    <AuthProvider>
      <Routes>
        <Route element={<PublicOnly />}>
          <Route path="/" element={<Navigate to="/login" replace />} />
          <Route path="/login" element={<AuthPage key="login" />} />
          <Route
            path="/register"
            element={<AuthPage key="register" register />}
          />
        </Route>
        <Route element={<Protected />}>
          <Route element={<AppShell />}>
            <Route path="/ask" element={<RepositoryPicker kind="ask" />} />
            <Route
              path="/search"
              element={<RepositoryPicker kind="search" />}
            />
            <Route path="/repositories/:id/architecture" element={<ArchitecturePage />} />
            <Route path="/repositories/:id/pull-requests" element={<PullRequestsPage />} />
            <Route path="/repositories/:id/pull-requests/:number" element={<PullRequestsPage />} />
            <Route path="/github" element={<GitHubPage />} />
            <Route path="/repositories" element={<RepositoriesPage />} />
            <Route
              path="/repositories/:id"
              element={<RepositoryDetailPage />}
            />
            <Route
              path="/repositories/:id/ask"
              element={<IntelligencePage kind="ask" />}
            />
            <Route
              path="/repositories/:id/search"
              element={<IntelligencePage kind="search" />}
            />
            {["dashboard", "settings"].map((page) => (
              <Route
                key={page}
                path={`/${page}`}
                element={<WorkspacePage page={page} />}
              />
            ))}
          </Route>
        </Route>
        <Route
          path="*"
          element={
            <main className="not-found">
              <h1>This page isn’t here.</h1>
              <Link to="/dashboard">Back to your workspace →</Link>
            </main>
          }
        />
      </Routes>
    </AuthProvider>
  </BrowserRouter>,
);
