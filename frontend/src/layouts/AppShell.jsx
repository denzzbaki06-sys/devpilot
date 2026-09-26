import { useEffect, useRef, useState } from "react";
import { version } from "../../package.json";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import {
  LayoutDashboard,
  FolderGit2,
  Sparkles,
  Search,
  Github,
  Settings,
  LogOut,
  Menu,
  X,
  ChevronRight,
  Command,
} from "lucide-react";
import Brand from "../components/Brand";
import { useAuth } from "../hooks/useAuth";
const navigation = [
  ["/dashboard", "Overview", LayoutDashboard],
  ["/repositories", "Repositories", FolderGit2],
];
export default function AppShell() {
  const { user, logout } = useAuth(),
    [open, setOpen] = useState(false),
    location = useLocation();
  const sidebar = useRef(null);
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement;
    const focusable = () => [...sidebar.current.querySelectorAll("a[href], button:not(:disabled)")];
    focusable()[0]?.focus();
    function keyboard(event) {
      if (event.key === "Escape") { event.preventDefault(); setOpen(false); }
      if (event.key === "Tab") {
        const items = focusable(), first = items[0], last = items.at(-1);
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    }
    const desktop = window.matchMedia?.("(min-width: 761px)");
    const resized = () => { if (desktop.matches) setOpen(false); };
    document.addEventListener("keydown", keyboard);
    desktop?.addEventListener("change", resized);
    return () => { document.removeEventListener("keydown", keyboard); desktop?.removeEventListener("change", resized); previous?.focus(); };
  }, [open]);
  const title =
    {
      "/dashboard": "Overview",
      "/repositories": "Repositories",
      "/github": "GitHub",
      "/settings": "Settings",
      "/ask": "Ask DevPilot",
      "/search": "Semantic Search",
    }[location.pathname] ||
    (location.pathname.includes("/architecture") ? "Architecture" : location.pathname.includes("/pull-requests")
      ? "Pull Requests"
      : location.pathname.endsWith("/ask")
      ? "Ask DevPilot"
      : location.pathname.endsWith("/search")
        ? "Semantic Search"
        : location.pathname.startsWith("/repositories/")
          ? "Repository workspace"
          : "Workspace");
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      {open && (
        <button
          className="scrim"
          aria-label="Close navigation"
          onClick={() => setOpen(false)}
        />
      )}
      <aside id="workspace-navigation" ref={sidebar} className={`sidebar ${open ? "open" : ""}`} role={open ? "dialog" : undefined} aria-modal={open ? true : undefined} aria-label="Workspace navigation">
        <div className="sidebar-brand">
          <Brand />
          <button
            className="icon-button mobile-only"
            aria-label="Close navigation"
            onClick={() => setOpen(false)}
          >
            <X size={20} />
          </button>
        </div>
        <div className="workspace-label">
          <span className="workspace-avatar">P</span>
          <div>
            Personal workspace<small>YOUR DEVELOPMENT SPACE</small>
          </div>
        </div>
        <nav aria-label="Main navigation">
          <span className="nav-label">WORKSPACE</span>
          {navigation.map(([to, label, Icon]) => (
            <NavLink
              key={to}
              to={to}
              end={to === "/repositories"}
              onClick={() => setOpen(false)}
            >
              <Icon size={18} />
              {label}
            </NavLink>
          ))}
          <NavLink
            to="/ask"
            onClick={() => setOpen(false)}
            className={
              location.pathname.endsWith("/ask") ? "active" : undefined
            }
          >
            <Sparkles size={18} />
            Ask DevPilot
          </NavLink>
          <NavLink
            to="/search"
            onClick={() => setOpen(false)}
            className={
              location.pathname.endsWith("/search") ? "active" : undefined
            }
          >
            <Search size={18} />
            Semantic Search
          </NavLink>
          <span className="nav-label secondary">MANAGE</span>
          <NavLink to="/github" onClick={() => setOpen(false)}>
            <Github size={18} />
            GitHub
          </NavLink>
          <NavLink to="/settings" onClick={() => setOpen(false)}>
            <Settings size={18} />
            Settings
          </NavLink>
        </nav>
        <div className="sidebar-bottom">
          <div className="foundation">
            <Command size={18} />
            <strong>
              A little context.
              <br />A lot of clarity.
            </strong>
            <p>Your codebase is the starting point.</p>
          </div>
          <button
            className="logout"
            onClick={() =>
              logout().catch(() => {
                /* Local session is already cleared. */
              })
            }
          >
            <LogOut size={17} />
            Sign out
          </button>
        </div>
      </aside>
      <div className="workspace-main" inert={open ? true : undefined}>
        <header className="topbar">
          <div>
            <button
              className="icon-button mobile-only"
              aria-label="Open navigation"
              aria-expanded={open}
              aria-controls="workspace-navigation"
              onClick={() => setOpen(true)}
            >
              <Menu size={20} />
            </button>
            <span className="muted">Workspace</span>
            <ChevronRight size={14} />
            <span>{title}</span>
          </div>
          <div className="user-identity">
            <span className="local-status">
              <span className="status-dot" />
              Personal workspace
            </span>
            <span className="avatar">
              {user.name.slice(0, 1).toUpperCase()}
            </span>
            <div>
              {user.name}
              <small>{user.email}</small>
            </div>
          </div>
        </header>
        <main id="main" className="page-content">
          <Outlet />
        </main>
        <footer className="workspace-footer">
          DEVPILOT <span>Built for understanding.</span>
          <span className="footer-right">WORKSPACE / v{version}</span>
        </footer>
      </div>
    </div>
  );
}
