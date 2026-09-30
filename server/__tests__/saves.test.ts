// Cloud saves route tests: auth is required on every route, per-user isolation,
// the size and count quotas reject with readable errors, and a save/replay
// round-trips. Uses a throwaway SQLite file (CAMPAIGN_DB_PATH must be set before
// the db module loads) and a real HTTP server so the Express middleware chain is
// exercised end to end.

import { describe, it, expect, beforeAll, afterAll } from "vitest";
import express from "express";
import type { Server } from "node:http";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import { createGame } from "../../src/engine/setup";
import { advanceCampaignWeek } from "../../src/engine/campaignWeek";

process.env.CAMPAIGN_DB_PATH = join(mkdtempSync(join(tmpdir(), "campaign-saves-test-")), "test.db");
process.env.JWT_SECRET = "test-jwt-secret";

const dbMod = await import("../db.ts");
const auth = await import("../auth.ts");
const { savesRouter, MAX_SAVES, MAX_RECORD_BYTES } = await import("../routes/saves.ts");

let server: Server;
let base: string;

function makeUser() {
  const id = randomUUID();
  const username = `u_${id.slice(0, 8)}`;
  dbMod.getDb().prepare(
    "INSERT INTO users (id, username, email, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
  ).run(id, username, `${username}@example.com`, "x", Date.now());
  return { id, username, token: auth.signToken({ userId: id, username }) };
}

async function req(method: string, path: string, token?: string, body?: unknown) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  return { status: res.status, json: json as Record<string, unknown> };
}

const sampleRecord = (over: Record<string, unknown> = {}) => ({
  name: "Test Save",
  turn: 4,
  playerCandidate: "dem",
  state: { turn: 4, note: "hello" },
  updatedAt: 1000,
  ...over,
});

beforeAll(async () => {
  dbMod.getDb();
  const app = express();
  app.use(express.json({ limit: "2304kb" }));
  app.use("/api/saves", savesRouter);
  await new Promise<void>((resolve) => {
    server = app.listen(0, () => {
      const addr = server.address();
      base = `http://127.0.0.1:${typeof addr === "object" && addr ? addr.port : 0}`;
      resolve();
    });
  });
});

afterAll(() => {
  server.close();
});

describe("auth is required", () => {
  it("rejects every route without a token", async () => {
    expect((await req("GET", "/api/saves")).status).toBe(401);
    expect((await req("GET", "/api/saves/x")).status).toBe(401);
    expect((await req("PUT", "/api/saves/x", undefined, sampleRecord())).status).toBe(401);
    expect((await req("DELETE", "/api/saves/x")).status).toBe(401);
    expect((await req("PUT", "/api/saves/x/replay", undefined, { log: {} })).status).toBe(401);
  });

  it("rejects a forged token", async () => {
    const bad = auth.signToken({ userId: "z", username: "z" }).slice(0, -2) + "xx";
    expect((await req("GET", "/api/saves", bad)).status).toBe(401);
  });
});

describe("round-trip and per-user isolation", () => {
  it("round-trips a finished campaign with its accumulated cause history", async () => {
    const a = makeUser();
    let game = createGame({seed: 42});
    while (game.phase !== "result") game = advanceCampaignWeek(game, "normal");
    expect(Buffer.byteLength(JSON.stringify(game))).toBeGreaterThan(200 * 1024);
    expect((await req("PUT", "/api/saves/full", a.token,
      sampleRecord({state: game, turn: game.turn, expectedUpdatedAt: null}))).status).toBe(200);
    const got = await req("GET", "/api/saves/full", a.token);
    expect(got.json.state).toEqual(game);
  });
  it("puts, lists, gets, and deletes a save scoped to the owner", async () => {
    const a = makeUser();
    const b = makeUser();

    const put = await req("PUT", "/api/saves/s1", a.token, sampleRecord());
    expect(put.status).toBe(200);

    const list = await req("GET", "/api/saves", a.token);
    expect(list.status).toBe(200);
    expect((list.json.saves as unknown[]).length).toBe(1);
    expect((list.json.saves as { id: string }[])[0].id).toBe("s1");

    const got = await req("GET", "/api/saves/s1", a.token);
    expect(got.status).toBe(200);
    expect((got.json.state as { note: string }).note).toBe("hello");
    expect(got.json.playerCandidate).toBe("dem");

    // Other user sees nothing and cannot read a's save.
    expect((await req("GET", "/api/saves", b.token)).json.saves).toEqual([]);
    expect((await req("GET", "/api/saves/s1", b.token)).status).toBe(404);

    const del = await req("DELETE", "/api/saves/s1", a.token);
    expect(del.status).toBe(200);
    expect((await req("GET", "/api/saves", a.token)).json.saves).toEqual([]);
  });

  it("attaches and reads a replay without wiping it on a later plain save", async () => {
    const a = makeUser();
    await req("PUT", "/api/saves/r1", a.token, sampleRecord());
    const attach = await req("PUT", "/api/saves/r1/replay", a.token, { log: { frames: [1, 2, 3] }, updatedAt: 2000 });
    expect(attach.status).toBe(200);

    // A plain snapshot update must preserve the existing replay.
    await req("PUT", "/api/saves/r1", a.token, sampleRecord({ name: "Renamed", updatedAt: 3000 }));

    const replay = await req("GET", "/api/saves/r1/replay", a.token);
    expect(replay.status).toBe(200);
    expect((replay.json.log as { frames: number[] }).frames).toEqual([1, 2, 3]);

    const list = await req("GET", "/api/saves", a.token);
    const meta = (list.json.saves as { name: string; hasReplay: boolean }[])[0];
    expect(meta.name).toBe("Renamed");
    expect(meta.hasReplay).toBe(true);
  });

  it("replay attach on a missing save is a 404", async () => {
    const a = makeUser();
    expect((await req("PUT", "/api/saves/nope/replay", a.token, { log: {} })).status).toBe(404);
  });
});

describe("quotas", () => {
  it("rejects an oversize record with a readable message", async () => {
    const a = makeUser();
    const big = "x".repeat(MAX_RECORD_BYTES + 1);
    const put = await req("PUT", "/api/saves/big", a.token, sampleRecord({ state: { blob: big } }));
    expect(put.status).toBe(413);
    expect(String(put.json.error)).toMatch(/too large/i);
    // Nothing was stored.
    expect((await req("GET", "/api/saves", a.token)).json.saves).toEqual([]);
  });

  it("rejects a new save past the per-user count limit but still updates existing ones", async () => {
    const a = makeUser();
    for (let i = 0; i < MAX_SAVES; i++) {
      const put = await req("PUT", `/api/saves/q${i}`, a.token, sampleRecord({ name: `S${i}` }));
      expect(put.status).toBe(200);
    }
    const over = await req("PUT", "/api/saves/one-too-many", a.token, sampleRecord());
    expect(over.status).toBe(409);
    expect(String(over.json.error)).toMatch(/limit/i);

    // Updating an existing save at the cap is still allowed.
    const update = await req("PUT", "/api/saves/q0", a.token, sampleRecord({ name: "Updated" }));
    expect(update.status).toBe(200);
  });
});

describe("version preconditions", () => {
  it("rejects stale and create-only uploads without replacing either snapshot or replay", async () => {
    const a = makeUser();
    expect((await req("PUT", "/api/saves/versioned", a.token,
      sampleRecord({expectedUpdatedAt: null, replay: {frames: [1]}}))).status).toBe(200);
    const stale = await req("PUT", "/api/saves/versioned", a.token,
      sampleRecord({updatedAt: 2000, expectedUpdatedAt: 900, state: {note: "stale"}, replay: null}));
    expect(stale.status).toBe(409);
    expect(stale.json.conflict).toBe(true);
    expect((await req("PUT", "/api/saves/versioned", a.token,
      sampleRecord({expectedUpdatedAt: null}))).status).toBe(409);
    const untouched = await req("GET", "/api/saves/versioned", a.token);
    expect(untouched.json.updatedAt).toBe(1000);
    expect(untouched.json.state).toEqual({turn: 4, note: "hello"});
    expect(untouched.json.replay).toEqual({frames: [1]});
    expect((await req("PUT", "/api/saves/versioned", a.token,
      sampleRecord({updatedAt: 2000, expectedUpdatedAt: 1000}))).status).toBe(200);
    expect((await req("GET", "/api/saves/versioned", a.token)).json.updatedAt).toBe(2000);
    expect((await req("PUT", "/api/saves/versioned", a.token,
      sampleRecord({updatedAt: 1000, expectedUpdatedAt: 2000}))).status).toBe(200);
    expect((await req("GET", "/api/saves/versioned", a.token)).json.updatedAt).toBe(2001);
  });
});
