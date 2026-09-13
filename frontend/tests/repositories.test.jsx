import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import {
  render,
  screen,
  waitFor,
  fireEvent,
  act,
} from "@testing-library/react";
import { MemoryRouter, Routes, Route, useLocation } from "react-router-dom";
import GitHubPage from "../src/pages/GitHubPage";
import RepositoriesPage from "../src/pages/RepositoriesPage";
import RepositoryDetailPage from "../src/pages/RepositoryDetailPage";
import WorkspacePage from "../src/pages/WorkspacePage";
import { useIndexStatus } from "../src/hooks/useIndexStatus";
import * as github from "../src/api/github";
import * as repos from "../src/api/repositories";
import {
  oauthNotice,
  authorizationDestination,
} from "../src/utils/repositoryUx";
vi.mock("../src/api/github", () => ({
  getGitHubStatus: vi.fn(),
  getGitHubRepositories: vi.fn(),
  startGitHubConnect: vi.fn(),
  disconnectGitHub: vi.fn(),
}));
vi.mock("../src/api/repositories", () => ({
  getConnectedRepositories: vi.fn(),
  getRepository: vi.fn(),
  connectRepository: vi.fn(),
  removeRepository: vi.fn(),
  startIndexing: vi.fn(),
  getIndexStatus: vi.fn(),
}));
vi.mock("../src/hooks/useAuth", () => ({
  useAuth: () => ({
    user: { name: "Ada", email: "ada@example.test", role: "USER" },
  }),
}));
const repo = {
  id: 12,
  githubRepositoryId: 44,
  fullName: "ada/codebase",
  primaryLanguage: "Java",
  privateRepository: true,
  defaultBranch: "main",
  status: "CONNECTED",
  connectedAt: "2026-09-12T00:00:00Z",
};
const remote = {
  githubRepositoryId: 44,
  fullName: "ada/codebase",
  language: "Java",
  privateRepository: true,
  defaultBranch: "main",
  updatedAt: "2026-09-12T00:00:00Z",
};
const status = {
  repositoryId: 12,
  status: "CONNECTED",
  filesIndexed: 0,
  chunksCreated: 0,
  embeddingsCreated: 0,
  lineCount: 0,
  job: null,
  lastError: null,
};
function Location() {
  return (
    <output data-testid="location">
      {useLocation().pathname}
      {useLocation().search}
    </output>
  );
}
function mount(element, path = "/github") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <Routes>
        <Route path="/github" element={element} />
        <Route path="/repositories/:id" element={element} />
        <Route
          path="/repositories"
          element={<div>Repository list destination</div>}
        />
        <Route path="/dashboard" element={element} />
      </Routes>
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.resetAllMocks();
  github.getGitHubStatus.mockResolvedValue({ connected: false, login: null });
  github.getGitHubRepositories.mockResolvedValue([remote]);
  repos.getConnectedRepositories.mockResolvedValue([]);
  repos.getRepository.mockResolvedValue(repo);
  repos.getIndexStatus.mockResolvedValue(status);
});
afterEach(() => vi.useRealTimers());
describe("GitHub UX", () => {
  it("renders disconnected state without fetching GitHub repositories", async () => {
    mount(<GitHubPage />);
    expect(
      await screen.findByRole("button", { name: "Connect GitHub" }),
    ).toBeTruthy();
    expect(github.getGitHubRepositories).not.toHaveBeenCalled();
  });
  it("renders connected status and maps remote backend metadata", async () => {
    github.getGitHubStatus.mockResolvedValue({ connected: true, login: "ada" });
    mount(<GitHubPage />);
    expect(await screen.findByText("ada/codebase")).toBeTruthy();
    expect(screen.getByText("Connected")).toBeTruthy();
    expect(screen.getAllByText("Private")).toHaveLength(2);
    expect(screen.getByText("main")).toBeTruthy();
  });
  it("adds correct repository and switches to open action", async () => {
    github.getGitHubStatus.mockResolvedValue({ connected: true, login: "ada" });
    repos.connectRepository.mockResolvedValue(repo);
    mount(<GitHubPage />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Add to DevPilot" }),
    );
    await waitFor(() =>
      expect(repos.connectRepository).toHaveBeenCalledWith(44),
    );
    expect(
      await screen.findByRole("link", { name: /Open repository/ }),
    ).toBeTruthy();
  });
  it("recovers duplicate connect using the current server list", async () => {
    github.getGitHubStatus.mockResolvedValue({ connected: true, login: "ada" });
    repos.connectRepository.mockRejectedValue({ response: { status: 409 } });
    mount(<GitHubPage />);
    await screen.findByRole("button", { name: "Add to DevPilot" });
    repos.getConnectedRepositories.mockResolvedValue([repo]);
    fireEvent.click(screen.getByRole("button", { name: "Add to DevPilot" }));
    expect(
      await screen.findByRole("link", { name: /Open repository/ }),
    ).toBeTruthy();
  });
  it("filters by name and visibility", async () => {
    github.getGitHubStatus.mockResolvedValue({ connected: true, login: "ada" });
    mount(<GitHubPage />);
    await screen.findByText("ada/codebase");
    fireEvent.click(screen.getByRole("button", { name: "Public" }));
    expect(screen.queryByText("ada/codebase")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "All" }));
    fireEvent.change(screen.getByLabelText("Search repositories"), {
      target: { value: "missing" },
    });
    expect(screen.getByText("No matching repositories.")).toBeTruthy();
  });
  it("shows safe config errors and keeps connect usable", async () => {
    github.startGitHubConnect.mockRejectedValue({
      response: {
        status: 503,
        data: { message: "GitHub OAuth is not configured" },
      },
    });
    mount(<GitHubPage />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Connect GitHub" }),
    );
    expect(
      await screen.findByText(/not configured on this server/),
    ).toBeTruthy();
  });
  it("disconnect requires confirmation and clears remote list", async () => {
    github.getGitHubStatus.mockResolvedValue({ connected: true, login: "ada" });
    github.disconnectGitHub.mockResolvedValue({});
    mount(<GitHubPage />);
    await screen.findByText("ada/codebase");
    fireEvent.click(screen.getByRole("button", { name: "Disconnect" }));
    expect(github.disconnectGitHub).not.toHaveBeenCalled();
    github.getGitHubStatus.mockResolvedValue({ connected: false, login: null });
    fireEvent.click(screen.getByRole("button", { name: "Disconnect GitHub" }));
    expect(
      await screen.findByRole("button", { name: "Connect GitHub" }),
    ).toBeTruthy();
    expect(screen.queryByText("ada/codebase")).toBeNull();
  });
  it.each([
    ["?connected=true", "GitHub authorization completed"],
    ["?error=untrusted-provider-text", "GitHub connection was not completed"],
  ])("consumes callback query %s", async (query, text) => {
    mount(<GitHubPage />, `/github${query}`);
    await screen.findByText(new RegExp(text));
    await waitFor(() =>
      expect(screen.getByTestId("location").textContent).toBe("/github"),
    );
    expect(screen.queryByText("untrusted-provider-text")).toBeNull();
  });
});
describe("Repository UX", () => {
  it("maps connected repositories including status", async () => {
    repos.getConnectedRepositories.mockResolvedValue([
      { ...repo, status: "READY" },
    ]);
    mount(<RepositoriesPage />);
    expect(await screen.findByText("ada/codebase")).toBeTruthy();
    expect(screen.getByText("READY")).toBeTruthy();
    expect(
      screen
        .getByRole("link", { name: /Open repository/ })
        .getAttribute("href"),
    ).toBe("/repositories/12");
  });
  it("starts indexing and shows accepted status", async () => {
    repos.startIndexing.mockResolvedValue({
      repositoryId: 12,
      jobId: 1,
      status: "INDEXING",
    });
    mount(<RepositoryDetailPage />, "/repositories/12");
    fireEvent.click(
      await screen.findByRole("button", { name: "Index repository" }),
    );
    expect(repos.startIndexing).toHaveBeenCalledWith("12");
  });
  it("renders FAILED and safe error with retry", async () => {
    repos.getIndexStatus.mockResolvedValue({
      ...status,
      status: "FAILED",
      lastError: "Embedding API key is not configured",
    });
    mount(<RepositoryDetailPage />, "/repositories/12");
    expect(
      await screen.findByText("Indexing could not be completed."),
    ).toBeTruthy();
    expect(
      screen.getByText("Embedding API key is not configured"),
    ).toBeTruthy();
    expect(screen.getByRole("button", { name: "Try again" })).toBeTruthy();
  });
  it("READY enables only placeholder routes", async () => {
    repos.getIndexStatus.mockResolvedValue({ ...status, status: "READY" });
    mount(<RepositoryDetailPage />, "/repositories/12");
    expect(
      await screen.findByText("Repository intelligence is ready."),
    ).toBeTruthy();
    expect(
      screen.getByRole("link", { name: /Ask DevPilot/ }).getAttribute("href"),
    ).toBe("/repositories/12/ask");
  });
  it("remove requires confirmation and navigates after success", async () => {
    repos.removeRepository.mockResolvedValue({});
    mount(<RepositoryDetailPage />, "/repositories/12");
    fireEvent.click(
      await screen.findByRole("button", { name: "Remove from DevPilot" }),
    );
    expect(repos.removeRepository).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(repos.removeRepository).not.toHaveBeenCalled();
    fireEvent.click(
      screen.getByRole("button", { name: "Remove from DevPilot" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Remove repository" }));
    expect(await screen.findByText("Repository list destination")).toBeTruthy();
    expect(repos.removeRepository).toHaveBeenCalledWith("12");
  });
  it("dashboard derives AI readiness from real repositories", async () => {
    repos.getConnectedRepositories.mockResolvedValue([
      { ...repo, status: "READY" },
    ]);
    mount(<WorkspacePage />, "/dashboard");
    expect(await screen.findByText("Ready to ask")).toBeTruthy();
    expect(screen.queryByText("Index a repository first")).toBeNull();
  });
});
function Probe() {
  const { data, error } = useIndexStatus("12");
  return <p>{error || data?.status || "loading"}</p>;
}
describe("polling lifecycle", () => {
  it("polls INDEXING sequentially and stops on READY", async () => {
    vi.useFakeTimers();
    repos.getIndexStatus
      .mockResolvedValueOnce({ ...status, status: "INDEXING" })
      .mockResolvedValueOnce({ ...status, status: "READY" });
    render(<Probe />);
    await act(async () => {});
    expect(repos.getIndexStatus).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2500);
    });
    expect(screen.getByText("READY")).toBeTruthy();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });
    expect(repos.getIndexStatus).toHaveBeenCalledTimes(2);
  });
  it("unmount aborts requests and clears pending polling", async () => {
    vi.useFakeTimers();
    repos.getIndexStatus.mockResolvedValue({ ...status, status: "INDEXING" });
    const view = render(<Probe />);
    await act(async () => {});
    const signal = repos.getIndexStatus.mock.calls[0][1];
    view.unmount();
    await vi.advanceTimersByTimeAsync(10000);
    expect(signal.aborted).toBe(true);
    expect(repos.getIndexStatus).toHaveBeenCalledTimes(1);
  });
  it("FAILED never schedules another poll", async () => {
    vi.useFakeTimers();
    repos.getIndexStatus.mockResolvedValue({ ...status, status: "FAILED" });
    render(<Probe />);
    await act(async () => {});
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });
    expect(repos.getIndexStatus).toHaveBeenCalledTimes(1);
  });
  it("polling error stops and is rendered", async () => {
    vi.useFakeTimers();
    repos.getIndexStatus.mockRejectedValue({ response: { status: 404 } });
    render(<Probe />);
    await act(async () => {});
    expect(screen.getByText(/not found/)).toBeTruthy();
    await vi.advanceTimersByTimeAsync(10000);
    expect(repos.getIndexStatus).toHaveBeenCalledTimes(1);
  });
});
it("OAuth URL is restricted to the genuine authorization endpoint", () => {
  expect(() =>
    authorizationDestination("https://evil.example/login/oauth/authorize"),
  ).toThrow();
  expect(oauthNotice("?error=anything").type).toBe("error");
});
