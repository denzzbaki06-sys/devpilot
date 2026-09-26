import { beforeEach, it, expect, vi } from "vitest";
import { render, screen, fireEvent, waitFor, act } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import ArchitecturePage, { ArchitectureWorkspace, ArchitectureResult } from "../src/pages/ArchitecturePage";
import { getArchitecture } from "../src/api/architecture";
import { getRepository } from "../src/api/repositories";
import { Protected } from "../src/routes/Guards";
import { useAuth } from "../src/hooks/useAuth";
vi.mock("../src/api/architecture", () => ({ getArchitecture: vi.fn() }));
vi.mock("../src/api/repositories", () => ({ getRepository: vi.fn() }));
vi.mock("../src/hooks/useAuth", () => ({ useAuth: vi.fn() }));
const components = [
  { id: "A", name: "AuthController", type: "CONTROLLER", metadata: { routes: [{ path: "/api/login", methods: ["POST"], symbol: "login" }] } },
  { id: "B", name: "AuthService", type: "SERVICE", metadata: {} },
  { id: "C", name: "UserRepository", type: "REPOSITORY", metadata: {} },
  { id: "D", name: "User", type: "ENTITY", metadata: { tableName: "users" } },
  { id: "E", name: "Application", type: "ENTRY_POINT", metadata: { entryPoint: true } },
].map(c => ({ ...c, language: "JAVA", path: `src/${c.name}.java`, qualifiedName: `test.${c.name}`, symbol: c.name, startLine: 3, endLine: 20 }));
const data = { repositoryId: 12, indexCommitSha: "a".repeat(40), components, relationships: [{ sourceComponentId: "A", targetComponentId: "B", type: "DEPENDS_ON", evidence: { path: "src/AuthController.java", startLine: 6, endLine: 6, symbol: "AuthController" } }], entryPoints: [components[4]], externalDependencies: [], stats: { components: 5, relationships: 1, entryPoints: 1, languages: ["JAVA"], filesAnalyzed: 5, filesSkipped: 0 }, warnings: [] };
function page() { return render(<MemoryRouter><ArchitectureWorkspace id="12" /></MemoryRouter>); }
function result(value = data) { return render(<MemoryRouter><ArchitectureResult data={value} /></MemoryRouter>); }
beforeEach(() => { getRepository.mockResolvedValue({ id: 12, status: "READY", fullName: "test/repo" }); getArchitecture.mockResolvedValue(data); useAuth.mockReturnValue({ status: "anonymous" }); });
it("Architecture tab is visible", () => { page(); expect(screen.getByRole("link", { name: "Architecture" }).getAttribute("href")).toBe("/repositories/12/architecture"); });
it("architecture route redirects unauthenticated users", async () => { render(<MemoryRouter initialEntries={["/repositories/12/architecture"]}><Routes><Route element={<Protected />}><Route path="/repositories/:id/architecture" element={<ArchitecturePage />} /></Route><Route path="/login" element={<p>Login required</p>} /></Routes></MemoryRouter>); expect(await screen.findByText("Login required")).not.toBeNull(); expect(getArchitecture).not.toHaveBeenCalled(); });
it("summary cards show real counts", () => { result(); expect(screen.getByText("5")).not.toBeNull(); expect(screen.getByText("Languages")).not.toBeNull(); expect(screen.getByText("Entry Points")).not.toBeNull(); });
it("only present component groups render", () => { result(); expect(screen.getByRole("heading", { name: "Controllers" })).not.toBeNull(); expect(screen.queryByRole("heading", { name: "Security" })).toBeNull(); });
it.each(["AuthController", "AuthService", "UserRepository", "User"])("%s component card", name => { result(); expect(screen.getByRole("heading", { name })).not.toBeNull(); });
it("component details expand with keyboard-native summary", () => { result(); const summary = screen.getByText("Component details · AuthController"); fireEvent.click(summary); expect(summary.closest("details").open).toBe(true); expect(screen.getByText("test.AuthController")).not.toBeNull(); });
it("incoming and outgoing relationships show actual names", () => { result(); expect(screen.getAllByText("Outgoing relationships")).toHaveLength(5); expect(screen.getAllByText("Incoming relationships")).toHaveLength(5); expect(screen.getByText("DEPENDS_ON → AuthService")).not.toBeNull(); expect(screen.getByText("AuthController → DEPENDS_ON")).not.toBeNull(); });
it("controller route metadata", () => { result(); expect(screen.getByText("POST /api/login")).not.toBeNull(); });
it("entity table metadata", () => { result(); expect(screen.getByText("users")).not.toBeNull(); });
it("entry points link to actual components", () => { result(); expect(screen.getByRole("link", { name: "Application" }).getAttribute("href")).toBe("#architecture-E"); });
it("loading state has no invented results", () => { getRepository.mockReturnValue(new Promise(() => {})); page(); expect(screen.getByRole("status").textContent).toContain("Analyzing repository structure"); expect(screen.queryByText("AuthController")).toBeNull(); });
it("non READY prevents analysis", async () => { getRepository.mockResolvedValue({ status: "INDEXING" }); page(); expect(await screen.findByText("Index repository before architecture analysis.")).not.toBeNull(); expect(getArchitecture).not.toHaveBeenCalled(); });
it("empty supported graph", () => { result({ ...data, components: [], entryPoints: [], relationships: [], stats: { ...data.stats, components: 0 } }); expect(screen.getByText("No supported architecture components")).not.toBeNull(); });
it("API error is safe", async () => { getArchitecture.mockRejectedValue({ response: { status: 500, data: { message: "internal-stack-secret" } } }); page(); expect(await screen.findByRole("alert")).not.toBeNull(); expect(screen.queryByText(/internal-stack-secret/)).toBeNull(); });
it("limit warning rendered", () => { result({ ...data, warnings: ["Architecture graph was limited to 10 components."] }); expect(screen.getByText("Architecture graph was limited to 10 components.")).not.toBeNull(); });
it("real source location and snapshot displayed", () => { result(); expect(screen.getAllByText(/Lines 3–20/).length).toBeGreaterThan(0); expect(screen.getByText("a".repeat(40))).not.toBeNull(); });
it("no graph data before response", async () => { getArchitecture.mockReturnValue(new Promise(() => {})); page(); await waitFor(() => expect(getArchitecture).toHaveBeenCalled()); expect(screen.queryByText("AuthService")).toBeNull(); });
it("removed repository has safe error", async () => { getRepository.mockRejectedValue({ response: { status: 404 } }); page(); expect(await screen.findByRole("alert")).not.toBeNull(); expect(getArchitecture).not.toHaveBeenCalled(); });
it("snapshot conflict asks reload", async () => { getArchitecture.mockRejectedValue({ response: { status: 409, data: { message: "Repository index changed; retry architecture analysis" } } }); page(); expect(await screen.findByText("The repository index changed. Reload to analyze the latest snapshot.")).not.toBeNull(); });
it("unmount aborts pending request", async () => { getArchitecture.mockReturnValue(new Promise(() => {})); const view = page(); await waitFor(() => expect(getArchitecture).toHaveBeenCalled()); const signal = getArchitecture.mock.calls[0][1]; view.unmount(); expect(signal.aborted).toBe(true); });
it("stale response cannot overwrite changed repository", async () => { let resolve; getArchitecture.mockReturnValueOnce(new Promise(r => { resolve = r; })); const view = page(); await waitFor(() => expect(getArchitecture).toHaveBeenCalled()); getRepository.mockResolvedValue({ id: 13, status: "CONNECTED" }); view.rerender(<MemoryRouter><ArchitectureWorkspace id="13" /></MemoryRouter>); await act(async () => resolve(data)); expect(screen.queryByRole("heading", { name: "AuthService" })).toBeNull(); });
it("wrong repository payload is rejected", async () => { getArchitecture.mockResolvedValue({ ...data, repositoryId: 14 }); page(); expect(await screen.findByRole("alert")).not.toBeNull(); expect(screen.queryByRole("heading", { name: "AuthService" })).toBeNull(); });
