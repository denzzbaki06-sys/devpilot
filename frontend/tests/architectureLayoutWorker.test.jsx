import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { requestArchitectureLayout } from "../src/components/architecture/layoutRequest";
import { architectureGraphAdapter } from "../src/components/architecture/architectureGraphAdapter";
import { graphFixture } from "./fixtures/architectureGraph";
let worker;
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("Worker", class {
    constructor() { worker = this; }
    postMessage = vi.fn();
    terminate = vi.fn();
  });
});
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
it("worker receives topology only, without component payloads", () => {
  const stop = requestArchitectureLayout(architectureGraphAdapter(graphFixture()), vi.fn(), vi.fn());
  const payload = worker.postMessage.mock.calls[0][0];
  expect(payload.nodes[0]).toEqual({ id: "backend-0000" });
  expect(Object.keys(payload.edges[0]).sort()).toEqual(["id", "source", "target"]);
  expect(payload.nodes.every(node => !("data" in node))).toBe(true); stop();
});
it("successful layout terminates worker and cancels timeout", () => {
  const done = vi.fn(), fail = vi.fn();
  requestArchitectureLayout(architectureGraphAdapter(graphFixture()), done, fail);
  const positions = [{ id: "backend-0000", position: { x: 1, y: 2 } }];
  worker.onmessage({ data: { positions } }); vi.runAllTimers();
  expect(done).toHaveBeenCalledWith(positions); expect(fail).not.toHaveBeenCalled(); expect(worker.terminate).toHaveBeenCalledTimes(1);
});
it("cleanup ignores stale worker results after navigation", () => {
  const done = vi.fn(), fail = vi.fn();
  const stop = requestArchitectureLayout(architectureGraphAdapter(graphFixture()), done, fail);
  stop(); worker.onmessage({ data: { positions: [] } }); worker.onerror(); vi.runAllTimers();
  expect(done).not.toHaveBeenCalled(); expect(fail).not.toHaveBeenCalled();
});
it("time budget terminates work and offers Components fallback", () => {
  const done = vi.fn(), fail = vi.fn();
  requestArchitectureLayout(architectureGraphAdapter(graphFixture()), done, fail);
  vi.advanceTimersByTime(15000); worker.onmessage({ data: { positions: [] } });
  expect(worker.terminate).toHaveBeenCalled(); expect(done).not.toHaveBeenCalled(); expect(fail).toHaveBeenCalledWith(expect.stringContaining("Components view"));
});
it("worker runtime error exposes a safe message", () => {
  const fail = vi.fn(); requestArchitectureLayout(architectureGraphAdapter(graphFixture()), vi.fn(), fail);
  worker.onerror({ message: "private stack trace" });
  expect(fail).toHaveBeenCalledWith(expect.stringContaining("Components view")); expect(fail.mock.calls[0][0]).not.toContain("private");
});
it("unavailable Worker still permits the Components fallback", () => {
  vi.stubGlobal("Worker", undefined); const fail = vi.fn();
  expect(() => requestArchitectureLayout(architectureGraphAdapter(graphFixture()), vi.fn(), fail)).not.toThrow();
  expect(fail).toHaveBeenCalledWith(expect.stringContaining("Components view"));
});
