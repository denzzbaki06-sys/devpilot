import { beforeEach, it, expect, vi } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import { MemoryRouter, Routes, Route, Link } from "react-router-dom";
import IntelligencePage from "../src/pages/IntelligencePage";
import RepositoryPicker from "../src/components/intelligence/RepositoryPicker";
import RepositoryDetailPage from "../src/pages/RepositoryDetailPage";
import AnswerText from "../src/components/intelligence/AnswerText";
import * as repositories from "../src/api/repositories";
import * as intelligence from "../src/api/intelligence";
import { intelligenceError, similarityLabel } from "../src/utils/intelligence";
vi.mock("../src/api/repositories", () => ({
  getRepository: vi.fn(),
  getConnectedRepositories: vi.fn(),
  getIndexStatus: vi.fn(),
  startIndexing: vi.fn(),
  removeRepository: vi.fn(),
}));
vi.mock("../src/api/intelligence", () => ({
  askRepository: vi.fn(),
  semanticSearch: vi.fn(),
}));
const repository = {
  id: 12,
  fullName: "ada/engine",
  status: "READY",
  primaryLanguage: "Java",
  defaultBranch: "main",
  privateRepository: true,
};
const source = {
  chunkId: 44,
  path: "src/security/JwtService.java",
  startLine: 31,
  endLine: 58,
  symbolName: "generateAccessToken",
  symbolType: "METHOD",
  language: "JAVA",
  similarity: 0.91,
};
const answer = {
  repositoryId: 12,
  question: "How is authentication structured?",
  answer: "The method creates a token. [1]",
  sources: [source],
  grounded: true,
  model: "fixture-model",
};
const chunk = {
  ...source,
  content:
    'public String token() {\n    return "<script>alert(1)</script>";\n}',
};
function mount(path = "/repositories/12/ask") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Link to="/repositories/13/search">Switch test repository</Link>
      <Routes>
        <Route path="/ask" element={<RepositoryPicker kind="ask" />} />
        <Route path="/search" element={<RepositoryPicker kind="search" />} />
        <Route
          path="/repositories/:id/ask"
          element={<IntelligencePage kind="ask" />}
        />
        <Route
          path="/repositories/:id/search"
          element={<IntelligencePage kind="search" />}
        />
        <Route path="/repositories/:id" element={<RepositoryDetailPage />} />
        <Route path="/repositories" element={<p>Repository list</p>} />
      </Routes>
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.resetAllMocks();
  repositories.getRepository.mockImplementation(async (id) => ({
    ...repository,
    id: Number(id),
  }));
  repositories.getConnectedRepositories.mockResolvedValue([repository]);
  repositories.getIndexStatus.mockResolvedValue({ status: "READY", job: null });
  intelligence.askRepository.mockResolvedValue(answer);
  intelligence.semanticSearch.mockResolvedValue({
    repositoryId: 12,
    query: "token",
    results: [chunk],
  });
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  });
});
async function ask(text = "How is authentication structured?") {
  const composer = await screen.findByLabelText("Your question");
  fireEvent.change(composer, { target: { value: text } });
  fireEvent.submit(screen.getByRole("form", { name: "Ask repository" }));
}
async function search(text = "token") {
  const input = await screen.findByLabelText("Search query");
  fireEvent.change(input, { target: { value: text } });
  fireEvent.submit(screen.getByRole("form", { name: "Search repository" }));
}
it("loads READY context and clearly labels suggestions as examples", async () => {
  mount();
  expect(await screen.findByLabelText("Your question")).toBeTruthy();
  expect(screen.getByText("ada/engine")).toBeTruthy();
  expect(screen.getByText("READY")).toBeTruthy();
  expect(screen.getByText(/EXAMPLE QUESTIONS/)).toBeTruthy();
  expect(intelligence.askRepository).not.toHaveBeenCalled();
});
it.each([
  [
    "CONNECTED",
    "Index this repository before using AI intelligence.",
    "Index repository",
  ],
  [
    "INDEXING",
    "Repository intelligence is being prepared.",
    "View indexing status",
  ],
  ["FAILED", "Indexing failed. Reindex to continue.", "Retry indexing"],
])("guards %s without AI calls", async (status, message, action) => {
  repositories.getRepository.mockResolvedValue({ ...repository, status });
  mount();
  expect(await screen.findByText(message)).toBeTruthy();
  expect(screen.getByRole("link", { name: action }).getAttribute("href")).toBe(
    "/repositories/12",
  );
  expect(screen.queryByLabelText("Your question")).toBeNull();
  expect(intelligence.askRepository).not.toHaveBeenCalled();
});
it("Ask submits standalone question and displays grounded answer/source metadata", async () => {
  mount();
  await ask();
  await screen.findByText("From your codebase");
  expect(intelligence.askRepository).toHaveBeenCalledWith(
    "12",
    "How is authentication structured?",
    undefined,
    expect.any(AbortSignal),
  );
  expect(screen.getByText("Grounded in repository sources")).toBeTruthy();
  expect(screen.getByText("src/security/JwtService.java")).toBeTruthy();
  expect(screen.getByText("Lines 31–58")).toBeTruthy();
  expect(screen.getByText("generateAccessToken · METHOD")).toBeTruthy();
  expect(screen.getByText("Similarity 91%")).toBeTruthy();
  expect(screen.getByText("fixture-model")).toBeTruthy();
});
it("valid citation focuses its real source and unknown citations stay plain text", async () => {
  intelligence.askRepository.mockResolvedValue({
    ...answer,
    answer: "Supported [1]. Unknown [999] and [S1].",
  });
  mount();
  await ask();
  fireEvent.click(
    await screen.findByRole("button", { name: "Go to source 1" }),
  );
  expect(document.activeElement.getAttribute("aria-label")).toBe(
    "Source 1: src/security/JwtService.java",
  );
  expect(screen.queryByRole("button", { name: "Go to source 999" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Go to source S1" })).toBeNull();
});
it("grounded false is neutral and produces no fake source cards", async () => {
  intelligence.askRepository.mockResolvedValue({
    ...answer,
    grounded: false,
    sources: [],
    answer: "Insufficient repository evidence.",
  });
  mount();
  await ask();
  expect(
    await screen.findByText("Not enough repository evidence"),
  ).toBeTruthy();
  expect(screen.getByText("No source references were returned.")).toBeTruthy();
  expect(screen.queryByLabelText(/Source 1:/)).toBeNull();
  expect(screen.queryByRole("alert")).toBeNull();
});
it("maps missing provider config without displaying raw provider data", async () => {
  intelligence.askRepository.mockRejectedValue({
    response: {
      status: 503,
      data: {
        message: "Chat API key is not configured",
        secret: "never-display",
      },
    },
  });
  mount();
  await ask();
  expect(
    await screen.findByText(
      "AI provider is not configured for this DevPilot instance.",
    ),
  ).toBeTruthy();
  expect(screen.queryByText("never-display")).toBeNull();
});
it("blocks blank Ask and applies the backend maximum", async () => {
  mount();
  const input = await screen.findByLabelText("Your question");
  expect(input.maxLength).toBe(4000);
  await ask("  ");
  expect(await screen.findByText(/between 1 and 4000/)).toBeTruthy();
  expect(intelligence.askRepository).not.toHaveBeenCalled();
});
it("rechecks READY before invoking a paid API", async () => {
  mount();
  await screen.findByLabelText("Your question");
  repositories.getRepository.mockResolvedValue({
    ...repository,
    status: "INDEXING",
  });
  await ask();
  expect(
    await screen.findByText("Repository intelligence is being prepared."),
  ).toBeTruthy();
  expect(intelligence.askRepository).not.toHaveBeenCalled();
});
it("Enter submits but Shift+Enter preserves multiline behavior", async () => {
  mount();
  const input = await screen.findByLabelText("Your question");
  fireEvent.change(input, { target: { value: "question" } });
  fireEvent.keyDown(input, { key: "Enter", shiftKey: true });
  expect(intelligence.askRepository).not.toHaveBeenCalled();
  fireEvent.keyDown(input, { key: "Enter" });
  await waitFor(() =>
    expect(intelligence.askRepository).toHaveBeenCalledTimes(1),
  );
});
it("Ask copy writes only the displayed answer", async () => {
  mount();
  await ask();
  fireEvent.click(await screen.findByRole("button", { name: "Copy answer" }));
  await waitFor(() =>
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(answer.answer),
  );
  expect(await screen.findByText("Copied")).toBeTruthy();
});
it("search explicitly submits query and bounded result limit", async () => {
  mount("/repositories/12/search");
  const input = await screen.findByLabelText("Search query");
  fireEvent.change(input, { target: { value: "token" } });
  expect(intelligence.semanticSearch).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("Result limit"), {
    target: { value: "20" },
  });
  fireEvent.submit(screen.getByRole("form", { name: "Search repository" }));
  await waitFor(() =>
    expect(intelligence.semanticSearch).toHaveBeenCalledWith(
      "12",
      "token",
      20,
      expect.any(AbortSignal),
    ),
  );
});
it("search preserves backend order, safe code whitespace and similarity", async () => {
  intelligence.semanticSearch.mockResolvedValue({
    repositoryId: 12,
    query: "token",
    results: [
      { ...chunk, chunkId: 9, path: "b.java", similarity: 0.87 },
      { ...chunk, path: "a.java", similarity: 0.63 },
    ],
  });
  mount("/repositories/12/search");
  await search();
  await screen.findByText("Results for “token”");
  const cards = screen.getAllByRole("article");
  expect(cards[0].textContent).toContain("b.java");
  expect(cards[1].textContent).toContain("a.java");
  expect(screen.getByText("87%")).toBeTruthy();
  expect(screen.getByLabelText("Code from b.java").textContent).toBe(
    chunk.content,
  );
  expect(document.querySelector("script")).toBeNull();
});
it("code copy preserves the exact source content", async () => {
  mount("/repositories/12/search");
  await search();
  fireEvent.click(await screen.findByRole("button", { name: "Copy code" }));
  await waitFor(() =>
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(chunk.content),
  );
});
it("search distinguishes initial state from empty result", async () => {
  intelligence.semanticSearch.mockResolvedValue({
    repositoryId: 12,
    query: "token",
    results: [],
  });
  mount("/repositories/12/search");
  expect(await screen.findByText("What are you looking for?")).toBeTruthy();
  await search();
  expect(
    await screen.findByText("No semantically relevant code was found."),
  ).toBeTruthy();
});
it("search rejects whitespace and uses the 2000 character limit", async () => {
  mount("/repositories/12/search");
  const input = await screen.findByLabelText("Search query");
  expect(input.maxLength).toBe(2000);
  await search("  ");
  expect(intelligence.semanticSearch).not.toHaveBeenCalled();
  expect(await screen.findByText(/between 1 and 2000/)).toBeTruthy();
});
it("picker contains only READY repository choices", async () => {
  repositories.getConnectedRepositories.mockResolvedValue([
    repository,
    { ...repository, id: 13, fullName: "ada/unready", status: "CONNECTED" },
  ]);
  mount("/ask?choose=true");
  expect(await screen.findByRole("link", { name: /ada\/engine/ })).toBeTruthy();
  expect(screen.queryByText("ada/unready")).toBeNull();
});
it("global Ask auto-selects the only READY repository", async () => {
  mount("/ask");
  expect(await screen.findByLabelText("Your question")).toBeTruthy();
  expect(repositories.getRepository).toHaveBeenCalledWith(
    "12",
    expect.any(AbortSignal),
  );
});
it("global Search supports choosing between READY repositories", async () => {
  repositories.getConnectedRepositories.mockResolvedValue([
    repository,
    { ...repository, id: 13, fullName: "ada/second" },
  ]);
  mount("/search");
  fireEvent.click(await screen.findByRole("link", { name: /ada\/second/ }));
  expect(await screen.findByLabelText("Search query")).toBeTruthy();
  expect(screen.getByText("ada/engine")).toBeTruthy();
  expect(repositories.getRepository).toHaveBeenCalledWith(
    "13",
    expect.any(AbortSignal),
  );
});
it("no READY repositories gives a real empty state", async () => {
  repositories.getConnectedRepositories.mockResolvedValue([]);
  mount("/ask");
  expect(await screen.findByText("No ready repositories yet.")).toBeTruthy();
  expect(screen.getByRole("link", { name: /Go to repositories/ })).toBeTruthy();
});
it("repository change aborts request and prevents old response overwriting new result", async () => {
  let resolveOld;
  intelligence.semanticSearch
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
    )
    .mockResolvedValueOnce({
      repositoryId: 13,
      query: "new query",
      results: [],
    });
  mount("/repositories/12/search");
  await search("old query");
  await waitFor(() =>
    expect(intelligence.semanticSearch).toHaveBeenCalledTimes(1),
  );
  const signal = intelligence.semanticSearch.mock.calls[0][3];
  fireEvent.click(screen.getByRole("link", { name: "Switch test repository" }));
  await screen.findByLabelText("Search query");
  expect(signal.aborted).toBe(true);
  await search("new query");
  expect(await screen.findByText("Results for “new query”")).toBeTruthy();
  await act(async () =>
    resolveOld({ repositoryId: 12, query: "old query", results: [chunk] }),
  );
  expect(screen.queryByText("Results for “old query”")).toBeNull();
  expect(screen.getByText("Results for “new query”")).toBeTruthy();
});
it("Cancel request rejects late response even if provider ignores abort", async () => {
  let resolve;
  intelligence.askRepository.mockImplementation(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  mount();
  await ask();
  await waitFor(() =>
    expect(intelligence.askRepository).toHaveBeenCalledTimes(1),
  );
  fireEvent.click(screen.getByRole("button", { name: "Cancel request" }));
  await act(async () => resolve(answer));
  expect(screen.queryByText("From your codebase")).toBeNull();
  expect(screen.getByRole("button", { name: "Ask DevPilot" }).disabled).toBe(
    false,
  );
});
it("detail Ask and Search links are live", async () => {
  mount("/repositories/12");
  expect(
    (await screen.findByRole("link", { name: "Ask" })).getAttribute("href"),
  ).toBe("/repositories/12/ask");
  expect(
    screen.getByRole("link", { name: "Search" }).getAttribute("href"),
  ).toBe("/repositories/12/search");
  fireEvent.click(screen.getByRole("link", { name: "Ask" }));
  expect(await screen.findByLabelText("Your question")).toBeTruthy();
});
it("answer content is text, fenced code never creates HTML or citations", () => {
  render(
    <AnswerText
      answer={
        "Literal <img src=x onerror=alert(1)> [1]\n```java\n<script>alert(1)</script> [1]\n```"
      }
      sources={[source]}
      onCitation={() => {}}
    />,
  );
  expect(document.querySelector("img")).toBeNull();
  expect(document.querySelector("script")).toBeNull();
  expect(
    screen.getAllByRole("button", { name: "Go to source 1" }),
  ).toHaveLength(1);
  expect(screen.getByLabelText("Answer code block").textContent).toContain(
    "<script>",
  );
});
it.each([
  [429, "rate limit"],
  [504, "timed out"],
  [502, "could not complete"],
  [403, "not accessible"],
  [409, "index changed"],
])("maps safe provider error %s", (status, message) => {
  expect(
    intelligenceError({
      response: { status, data: { message: "raw secret provider body" } },
    }),
  ).toContain(message);
});
it("similarity is percentage of actual score, not confidence or a fabricated fallback", () => {
  expect(similarityLabel(0.87)).toBe("87%");
  expect(similarityLabel(0)).toBe("0%");
  expect(similarityLabel(undefined)).toBe("Not available");
});
