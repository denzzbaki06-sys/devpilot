import { useEffect, useState, useCallback } from "react";
import { getIndexStatus } from "../api/repositories";
import { repositoryError } from "../utils/repositoryUx";
// Recursive timeout schedules only after a completed request: no overlapping polls.
export function useIndexStatus(id) {
  const [data, setData] = useState(null),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true),
    [version, setVersion] = useState(0);
  const refresh = useCallback(() => setVersion((v) => v + 1), []);
  useEffect(() => {
    let active = true,
      timer;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    setData(null);
    async function load() {
      try {
        const next = await getIndexStatus(id, controller.signal);
        if (!active) return;
        setData(next);
        setLoading(false);
        if (next.status === "INDEXING") timer = setTimeout(load, 2500);
      } catch (err) {
        if (!active) return;
        setError(repositoryError(err));
        setLoading(false);
      }
    }
    load();
    return () => {
      active = false;
      controller.abort();
      clearTimeout(timer);
    };
  }, [id, version]);
  return { data, error, loading, refresh };
}
