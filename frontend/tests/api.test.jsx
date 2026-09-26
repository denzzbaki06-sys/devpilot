import { it, expect, vi } from "vitest";
vi.mock("../src/api/client", () => ({
  api: {
    get: vi.fn().mockResolvedValue({ data: {} }),
    post: vi.fn().mockResolvedValue({ data: {} }),
    delete: vi.fn().mockResolvedValue({}),
  },
}));
import { api } from "../src/api/client";
import * as github from "../src/api/github";
import * as repos from "../src/api/repositories";
it("repository API uses the verified request contracts", async () => {
  await repos.connectRepository(99);
  expect(api.post).toHaveBeenCalledWith("/api/repositories", {
    githubRepositoryId: 99,
  });
  await repos.startIndexing(12);
  expect(api.post).toHaveBeenCalledWith("/api/repositories/12/index");
  await repos.getIndexStatus(12);
  expect(api.get).toHaveBeenCalledWith("/api/repositories/12/index-status", {
    signal: undefined,
  });
  await repos.removeRepository(12);
  expect(api.delete).toHaveBeenCalledWith("/api/repositories/12");
});
it("OAuth cookie is enabled only on the connect/disconnect calls", async () => {
  await github.startGitHubConnect();
  expect(api.get).toHaveBeenCalledWith("/api/github/connect", {
    withCredentials: true,
  });
  await github.disconnectGitHub();
  expect(api.post).toHaveBeenCalledWith("/api/github/disconnect", null, {
    withCredentials: true,
  });
});

import { askRepository, semanticSearch } from "../src/api/intelligence";
it("AI API follows Ask/Search contracts and forwards cancellation with sufficient timeout", async () => {
  const signal = new AbortController().signal;
  await askRepository(12, "question", undefined, signal);
  expect(api.post).toHaveBeenCalledWith(
    "/api/repositories/12/ask",
    { question: "question" },
    { signal, timeout: 180000 },
  );
  await semanticSearch(12, "query", 8, signal);
  expect(api.post).toHaveBeenCalledWith(
    "/api/repositories/12/search",
    { query: "query", limit: 8 },
    { signal, timeout: 180000 },
  );
});

import { getPullRequests, getPullRequest } from "../src/api/pullRequests";
it("PR API forwards bounded page inputs, detail number and cancellation", async () => {
  const signal = new AbortController().signal;
  await getPullRequests(12, 2, 20, signal);
  expect(api.get).toHaveBeenCalledWith("/api/repositories/12/pull-requests", { params: { page: 2, size: 20 }, signal });
  await getPullRequest(12, 42, signal);
  expect(api.get).toHaveBeenCalledWith("/api/repositories/12/pull-requests/42", { signal, timeout: 180000 });
});

import { reviewPullRequest } from "../src/api/pullRequests";
it("review API posts snapshot constraints with cancellation", async () => {
  const signal = new AbortController().signal, body = { expectedHeadSha: "a".repeat(40) };
  await reviewPullRequest(12, 42, body, signal);
  expect(api.post).toHaveBeenCalledWith("/api/repositories/12/pull-requests/42/review", body, { signal, timeout: 900000 });
});

import { getArchitecture } from "../src/api/architecture";
it("architecture API forwards cancellation without AI request data", async () => {
  const controller = new AbortController();
  await getArchitecture(12, controller.signal);
  expect(api.get).toHaveBeenCalledWith("/api/repositories/12/architecture", { signal: controller.signal, timeout: 60000 });
});

import { askArchitecture } from "../src/api/architecture";
it("architecture ask endpoint forwards the bounded DTO and AbortSignal", async () => {
  const signal = new AbortController().signal;
  const request = { question: "Explain", selectedComponentId: "actual-id", indexCommitSha: "a".repeat(40) };
  await askArchitecture(12, request, signal);
  expect(api.post).toHaveBeenCalledWith("/api/repositories/12/architecture/ask", request, { signal, timeout: 420000 });
});
