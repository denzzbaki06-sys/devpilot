import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";
afterEach(cleanup);
// jsdom has no native modal rendering; preserve open/focus behavior for component tests.
HTMLDialogElement.prototype.showModal = function () {
  this.open = true;
  this.querySelector("[autofocus]")?.focus();
};
HTMLDialogElement.prototype.close = function () {
  this.open = false;
};
