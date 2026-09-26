import { it, expect, vi } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import AppShell from "../src/layouts/AppShell";
import { RepositoryNavigation } from "../src/components/RepositoryUi";
import { intelligenceError } from "../src/utils/intelligence";
import { reviewError } from "../src/components/review/ReviewPanel";
vi.mock("../src/hooks/useAuth", () => ({ useAuth: () => ({ user: { name: "Local Tester", email: "local@example.test" }, logout: vi.fn().mockResolvedValue() }) }));
it.each(["Chat API key is not configured", "Embedding API key is not configured", "Embedding provider is not configured", "Embedding dimensions do not match database vector(1536)"])("AI surfaces agree on missing configuration: %s", message => {
  const error = { response: { status: 503, data: { message } } };
  expect(intelligenceError(error)).toBe("AI provider is not configured for this DevPilot instance.");
  expect(reviewError(error)).toBe(intelligenceError(error));
});
it("transient AI unavailability is not labelled missing configuration", () => {
  const error = { response: { status: 503, data: { message: "Embedding operation interrupted; retry indexing" } } };
  expect(reviewError(error)).toBe("AI provider is temporarily unavailable. Please try again later.");
  expect(intelligenceError(error)).toBe(reviewError(error));
});
it("GitHub errors remain distinct on the review surface", () => {
  expect(reviewError({ response: { status: 401, data: { message: "GitHub authorization expired or revoked; reconnect GitHub" } } })).toContain("Reconnect GitHub");
});
it.each(["ask", "search"])("%s navigation identifies the active route and keeps all workspace links", active => {
  render(<MemoryRouter><RepositoryNavigation id={12} active={active} /></MemoryRouter>);
  const nav = screen.getByRole("navigation", { name: "Repository sections" });
  expect(within(nav).getAllByRole("link")).toHaveLength(6);
  expect(nav.querySelector('[aria-current="page"]').getAttribute("href")).toBe(`/repositories/12/${active}`);
  expect(within(nav).getByRole("link", { name: "Indexing" }).getAttribute("href")).toBe("/repositories/12?section=indexing");
});
it("navigation drawer traps focus, closes with Escape and restores the opener", () => {
  render(<MemoryRouter><AppShell /></MemoryRouter>);
  const opener = screen.getByRole("button", { name: "Open navigation" });
  opener.focus(); fireEvent.click(opener);
  const dialog = screen.getByRole("dialog", { name: "Workspace navigation" });
  const close = within(dialog).getByRole("button", { name: "Close navigation" });
  expect(document.activeElement).toBe(close);
  expect(document.querySelector(".workspace-main").hasAttribute("inert")).toBe(true);
  fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
  expect(document.activeElement).toBe(within(dialog).getByRole("button", { name: "Sign out" }));
  fireEvent.keyDown(document, { key: "Tab" }); expect(document.activeElement).toBe(close);
  fireEvent.keyDown(document, { key: "Escape" });
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(document.activeElement).toBe(opener);
  expect(document.querySelector(".workspace-main").hasAttribute("inert")).toBe(false);
});
