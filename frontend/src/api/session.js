// Access tokens never persist. Refresh tokens survive reloads only in this tab.
export function createSession(storage) {
  let access = null;
  let refresh = null;
  let revision = 0;
  const listeners = new Set();
  try {
    refresh = storage?.getItem("devpilot.refresh") || null;
  } catch {
    /* Memory-only fallback. */
  }
  function persist() {
    try {
      if (refresh) storage?.setItem("devpilot.refresh", refresh);
      else storage?.removeItem("devpilot.refresh");
    } catch {
      /* Storage may be disabled. */
    }
  }
  return {
    access: () => access,
    refresh: () => refresh,
    revision: () => revision,
    set(data) {
      access = data.accessToken;
      refresh = data.refreshToken;
      revision++;
      persist();
      listeners.forEach((fn) => fn(data.user));
    },
    clear() {
      access = null;
      refresh = null;
      revision++;
      persist();
      listeners.forEach((fn) => fn(null));
    },
    subscribe(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
  };
}
