import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  ArrowRight,
  Eye,
  EyeOff,
  GitBranch,
  ScanLine,
  Sparkles,
} from "lucide-react";
import Brand from "../components/Brand";
import { ErrorMessage } from "../components/Feedback";
import { useAuth } from "../hooks/useAuth";
import { errorMessage } from "../utils/errors";
export default function AuthPage({ register = false }) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [visible, setVisible] = useState(false);
  const auth = useAuth(),
    navigate = useNavigate(),
    location = useLocation();
  async function submit(event) {
    event.preventDefault();
    setError("");
    const values = Object.fromEntries(new FormData(event.currentTarget));
    values.email = values.email.trim();
    if (register) values.name = values.name.trim();
    if (
      (register && !values.name) ||
      new TextEncoder().encode(values.password).length > 72
    ) {
      setError("Enter a name and a password of at most 72 UTF-8 bytes.");
      return;
    }
    setBusy(true);
    try {
      await (register ? auth.register : auth.login)(values);
      const from = location.state?.from;
      const safe =
        typeof from === "string" &&
        /^\/(dashboard|ask|search|repositories(?:\/\d+(?:\/(ask|search|architecture|pull-requests(?:\/\d+)?))?)?|github|settings)(?:\?[^#]*)?$/.test(
          from,
        );
      navigate(safe ? from : "/dashboard", { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }
  return (
    <main className="auth-page">
      <section className="auth-story">
        <Link to="/" className="brand-link">
          <Brand />
        </Link>
        <div className="story-copy">
          <div className="eyebrow">
            <span className="status-dot" /> YOUR CODE, IN CONTEXT
          </div>
          <h1>
            Less searching.
            <br />
            More <span>understanding.</span>
          </h1>
          <p>
            Connect repositories, search semantically, and ask questions
            grounded in real source code.
          </p>
          <div className="code-preview">
            <div className="code-title">
              <GitBranch size={15} /> A clearer view of your codebase{" "}
              <span>DEV / PILOT</span>
            </div>
            <div className="code-line">
              <i>01</i>
              <span className="purple">const</span> clarity ={" "}
              <span className="blue">await</span> devpilot
              <span className="muted">.understand</span>({"{"}
            </div>
            <div className="code-line">
              <i>02</i> repository:{" "}
              <span className="green">'your-next-big-idea'</span>,
            </div>
            <div className="code-line">
              <i>03</i> context:{" "}
              <span className="green">'the source of truth'</span>
            </div>
            <div className="code-line">
              <i>04</i>
              {"}"});
            </div>
            <div className="code-note">
              <ScanLine size={15} /> Built around your source. Designed for your
              flow.
            </div>
          </div>
          <div className="story-features">
            <span>
              <GitBranch size={16} /> Repository aware
            </span>
            <span>
              <Sparkles size={16} /> Context first
            </span>
          </div>
        </div>
        <footer>Developer intelligence, without the guesswork.</footer>
      </section>
      <section className="auth-form-side">
        <div className="auth-form-wrap">
          <span className="small-badge">YOUR WORKSPACE STARTS HERE</span>
          <h2>{register ? "Build with more context." : "Welcome back."}</h2>
          <p className="muted">
            {register
              ? "Create your DevPilot account."
              : "Sign in to your DevPilot workspace."}
          </p>
          <form onSubmit={submit}>
            <ErrorMessage>{error}</ErrorMessage>
            {register && (
              <label>
                Full name
                <input
                  name="name"
                  autoComplete="name"
                  placeholder="Alex Morgan"
                  required
                  maxLength={100}
                  disabled={busy}
                />
              </label>
            )}
            <label>
              Email address
              <input
                name="email"
                type="email"
                autoComplete="email"
                placeholder="you@company.com"
                required
                maxLength={254}
                disabled={busy}
              />
            </label>
            <label>
              Password
              <div className="password-field">
                <input
                  name="password"
                  type={visible ? "text" : "password"}
                  autoComplete={register ? "new-password" : "current-password"}
                  placeholder={
                    register ? "At least 8 characters" : "Enter your password"
                  }
                  required
                  minLength={8}
                  maxLength={72}
                  disabled={busy}
                />
                <button
                  type="button"
                  className="eye"
                  aria-label={visible ? "Hide password" : "Show password"}
                  aria-pressed={visible}
                  onClick={() => setVisible(!visible)}
                >
                  {visible ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
            </label>
            {register && (
              <p className="field-hint">
                8–72 characters; maximum 72 UTF-8 bytes.
              </p>
            )}
            <button className="primary" disabled={busy}>
              {busy ? "Please wait…" : register ? "Create account" : "Sign in"}
              <ArrowRight size={17} />
            </button>
          </form>
          <p className="auth-switch">
            {register ? "Already have an account?" : "New to DevPilot?"}{" "}
            <Link to={register ? "/login" : "/register"}>
              {register ? "Sign in" : "Create an account"}
            </Link>
          </p>
          <div className="auth-footnote">
            <span className="status-dot" /> A focused workspace for thoughtful
            developers.
          </div>
        </div>
      </section>
    </main>
  );
}
