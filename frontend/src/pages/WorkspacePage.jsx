import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  Github,
  FolderGit2,
  Layers,
  ArrowUpRight,
  Sparkles,
  ArrowRight,
} from "lucide-react";
import { getConnectedRepositories } from "../api/repositories";
import { getGitHubStatus } from "../api/github";
import { workspaceSummary } from "../utils/repositoryUx";
import { useAuth } from "../hooks/useAuth";
import { ErrorMessage } from "../components/Feedback";
import { errorMessage } from "../utils/errors";
export default function WorkspacePage({ page = "dashboard" }) {
  const { user } = useAuth(),
    [data, setData] = useState(null),
    [error, setError] = useState(""),
    [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    setError("");
    setData(null);
    Promise.all([page === "settings" ? Promise.resolve([]) : getConnectedRepositories(), getGitHubStatus()])
      .then(([repos, github]) => {
        if (active) setData({ repos, github });
      })
      .catch((err) => {
        if (active) setError(errorMessage(err));
      });
    return () => {
      active = false;
    };
  }, [page, attempt]);
  const titles = {
    dashboard: `Welcome back, ${user.name.split(" ")[0]}.`,
    repositories: "Your repositories.",
    github: "Connect your code.",
    settings: "Make yourself at home.",
  };
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">
            {page === "dashboard"
              ? "YOUR WORKSPACE, AT A GLANCE"
              : page.toUpperCase()}
          </div>
          <h1>{titles[page]}</h1>
          <p className="muted">
            {page === "dashboard"
              ? "Great work starts with a little context. Here’s yours."
              : "A focused home for your development workflow."}
          </p>
        </div>
        <span className="outline-badge">PERSONAL WORKSPACE</span>
      </div>
      {error && (
        <>
          <ErrorMessage>{error}</ErrorMessage>
          <button
            className="secondary-button"
            onClick={() => setAttempt((v) => v + 1)}
          >
            Try again
          </button>
        </>
      )}
      {page === "dashboard" ? (
        <>
          <section className="hero-panel">
            <div>
              <span className="eyebrow">FROM SOURCE TO UNDERSTANDING</span>
              <h2>
                Your next insight
                <br />
                starts with your code.
              </h2>
              <p>
                Bring your repositories into one thoughtful workspace.
                <br /> Build a clearer picture of how everything fits together.
              </p>
              <Link className="primary inline" to="/github">
                Explore GitHub connection
                <ArrowRight size={17} />
              </Link>
            </div>
            <div className="orbit-art" aria-hidden="true">
              <div className="orbit outer" />
              <div className="orbit inner" />
              <div className="orbit-core">
                <FolderGit2 size={35} />
              </div>
              <span className="orbit-node n1">
                <Github size={22} />
              </span>
              <span className="orbit-node n2">
                <Layers size={22} />
              </span>
              <span className="orbit-node n3">
                <Sparkles size={20} />
              </span>
            </div>
          </section>
          <div className="stats-grid">
            <Stat
              icon={FolderGit2}
              title="Connected repositories"
              value={data ? String(data.repos.length) : "—"}
              note={
                data
                  ? "Connected to your workspace"
                  : error
                    ? "Could not load"
                    : "Loading workspace…"
              }
            />
            <Stat
              icon={Layers}
              title="Indexed repositories"
              value={
                data
                  ? String(
                      data.repos.filter((r) => r.status === "READY").length,
                    )
                  : "—"
              }
              note="Repositories with a ready index"
            />
            <Stat
              icon={Github}
              title="GitHub connection"
              value={
                data
                  ? data.github.connected
                    ? "Connected"
                    : "Not connected"
                  : "—"
              }
              note={data?.github.login || "Your source code connection"}
            />
            <Stat
              icon={Sparkles}
              title="AI workspace"
              value={data ? workspaceSummary(data.repos).ai : "—"}
              note={
                data && workspaceSummary(data.repos).ready
                  ? "Ask DevPilot using repository sources"
                  : "Index a repository to unlock AI workspace."
              }
              to="/ask"
            />
          </div>
          <div className="section-heading">
            <h2>A good place to start</h2>
            <span className="mono muted">ONE STEP AT A TIME</span>
          </div>
          <div className="action-grid">
            <Action
              to="/github"
              icon={Github}
              title="Connect GitHub"
              description="See your connection status and prepare your workspace."
            />
            <Action
              to="/repositories"
              icon={FolderGit2}
              title="Explore repositories"
              description="See the repositories connected to your account."
            />
            <Action
              to="/ask"
              icon={Sparkles}
              title="Ask DevPilot"
              description={
                data && workspaceSummary(data.repos).ready
                  ? "Choose a ready repository and follow the source."
                  : "Index a repository to unlock AI workspace."
              }
            />
          </div>
        </>
      ) : page === "settings" ? (
        <section className="panel profile">
          <span className="large-avatar">
            {user.name.slice(0, 1).toUpperCase()}
          </span>
          <div>
            <h2>{user.name}</h2>
            <p className="muted">{user.email}</p>
            <span className="outline-badge">{user.role}</span>
          </div>
          <div className="settings-note">
            <Github size={22} />
            <h3>GitHub connection</h3>
            <p className="muted">
              {data ? (data.github.connected ? `Connected as ${data.github.login}` : "Not connected") : error ? "Connection status unavailable" : "Checking connection…"}
            </p>
            <Link className="text-action" to="/github">Manage GitHub connection →</Link>
            <p className="muted">AI availability is checked when you index, search, or ask a repository.</p>
          </div>
        </section>
      ) : null}
    </>
  );
}
function Stat({ icon: Icon, title, value, note, to }) {
  return (
    <article className="stat-card">
      <div>
        <Icon size={18} />
        <span>{title}</span>
      </div>
      <strong>{value}</strong>
      <small>{note}</small>
      {to && (
        <Link className="text-action stat-link" to={to}>
          Open AI workspace →
        </Link>
      )}
    </article>
  );
}
function Action({ icon: Icon, title, description, to }) {
  return (
    <Link className="action-card" to={to}>
      <Icon />
      <ArrowUpRight className="action-arrow" size={18} />
      <h3>{title}</h3>
      <p>{description}</p>
    </Link>
  );
}
