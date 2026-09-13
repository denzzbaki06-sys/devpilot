import test from "node:test";
import assert from "node:assert/strict";
import { AxiosError } from "axios";
import { createApi } from "../src/api/createClient.js";
import { createSession } from "../src/api/session.js";
const credentials = {
  accessToken: "old",
  refreshToken: "refresh-old",
  user: { id: 1 },
};
function unauthorized(config) {
  throw new AxiosError("Unauthorized", "ERR_BAD_REQUEST", config, null, {
    status: 401,
    config,
  });
}
const ok = (config, data) => ({ status: 200, data, config, headers: {} });
test("concurrent 401 responses share one rotating refresh and retry", async () => {
  const session = createSession();
  session.set(credentials);
  let rotations = 0;
  const adapter = async (config) => {
    if (config.url === "/api/auth/refresh") {
      rotations++;
      await new Promise((r) => setTimeout(r, 15));
      return ok(config, {
        ...credentials,
        accessToken: "new",
        refreshToken: "refresh-new",
      });
    }
    if (config.headers.get("Authorization") === "Bearer old")
      return unauthorized(config);
    return ok(config, { id: 1 });
  };
  const client = createApi("http://test", session, adapter);
  await Promise.all(
    Array.from({ length: 8 }, () => client.api.get("/api/users/me")),
  );
  assert.equal(rotations, 1);
  assert.equal(session.refresh(), "refresh-new");
});
test("refresh failure clears state without an infinite loop", async () => {
  const session = createSession();
  session.set(credentials);
  let calls = 0;
  const client = createApi("http://test", session, async (config) => {
    calls++;
    return unauthorized(config);
  });
  await assert.rejects(client.api.get("/api/users/me"));
  assert.equal(calls, 2);
  assert.equal(session.access(), null);
  assert.equal(session.refresh(), null);
});
test("logout during refresh revokes the rotated token", async () => {
  const session = createSession();
  session.set(credentials);
  let revoked;
  const client = createApi("http://test", session, async (config) => {
    if (config.url.endsWith("refresh")) {
      await new Promise((r) => setTimeout(r, 10));
      return ok(config, { ...credentials, refreshToken: "rotated" });
    }
    revoked = JSON.parse(config.data).refreshToken;
    return ok(config, {});
  });
  const refreshing = client.refresh();
  await client.logout();
  await refreshing;
  assert.equal(revoked, "rotated");
  assert.equal(session.access(), null);
});
test("access is memory only and refresh persists in provided session storage", () => {
  const values = new Map();
  const storage = {
    getItem: (key) => values.get(key),
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
  const first = createSession(storage);
  first.set(credentials);
  const restored = createSession(storage);
  assert.equal(restored.access(), null);
  assert.equal(restored.refresh(), "refresh-old");
  first.clear();
  assert.equal(values.size, 0);
});
test("late refresh cannot restore a cleared session", async () => {
  const session = createSession();
  session.set(credentials);
  const client = createApi("http://test", session, async (config) => {
    await new Promise((r) => setTimeout(r, 5));
    return ok(config, credentials);
  });
  const pending = client.refresh();
  session.clear();
  await assert.rejects(pending);
  assert.equal(session.access(), null);
});

test("expired GitHub authorization does not rotate or clear the DevPilot session", async () => {
  const session = createSession();
  session.set(credentials);
  let calls = 0;
  const client = createApi("http://test", session, async (config) => {
    calls++;
    throw new AxiosError(
      "Upstream authorization expired",
      "ERR_BAD_REQUEST",
      config,
      null,
      {
        status: 401,
        config,
        data: {
          message: "GitHub authorization expired or revoked; reconnect GitHub",
        },
      },
    );
  });
  await assert.rejects(client.api.get("/api/github/repositories"));
  assert.equal(calls, 1);
  assert.equal(session.access(), "old");
  assert.equal(session.refresh(), "refresh-old");
});
